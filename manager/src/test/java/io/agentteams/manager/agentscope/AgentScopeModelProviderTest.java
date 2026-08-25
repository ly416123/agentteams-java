package io.agentteams.manager.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentteams.manager.InMemoryModelPriceCatalog;
import io.agentteams.manager.ManagerSessionService;
import io.agentteams.manager.ManagerToolRegistry;
import io.agentteams.manager.ModelCallAdmission;
import io.agentteams.manager.ModelCallAdmissionRequest;
import io.agentteams.manager.ModelCallAudit;
import io.agentteams.manager.ModelCallLease;
import io.agentteams.manager.ModelCostCalculator;
import io.agentteams.manager.ModelPrice;
import io.agentteams.manager.ModelProvider;
import io.agentteams.manager.ModelProviderException;
import io.agentteams.manager.ModelProviderRegistry;
import io.agentteams.manager.ReloadableModelProvider;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class AgentScopeModelProviderTest {
    @Test
    void convertsManagerRequestAndAggregatesOnlyTextResponses() {
        AtomicReference<List<io.agentscope.core.message.Msg>> messages = new AtomicReference<>();
        AtomicReference<GenerateOptions> options = new AtomicReference<>();
        Model model = new RecordingModel("deepseek-chat", (receivedMessages, receivedOptions) -> {
            messages.set(receivedMessages);
            options.set(receivedOptions);
            return Flux.just(
                    response(List.of(text("first")), new ChatUsage(3, 0, 0)),
                    response(List.of(text(" second")), new ChatUsage(3, 4, 0)));
        });
        AgentScopeModelProvider provider = new AgentScopeModelProvider(model, "deepseek");

        ModelProvider.ModelResponse response = provider.complete(
                new ModelProvider.ModelRequest("private prompt", 64));

        assertThat(messages.get()).singleElement().isInstanceOf(UserMessage.class);
        assertThat(messages.get().get(0).getTextContent()).isEqualTo("private prompt");
        assertThat(options.get().getMaxTokens()).isEqualTo(64);
        assertThat(options.get().getModelName()).isEqualTo("deepseek-chat");
        assertThat(options.get().getStream()).isTrue();
        assertThat(options.get().getApiKey()).isNull();
        assertThat(response.content()).isEqualTo("first second");
        assertThat(response.model()).isEqualTo("deepseek-chat");
        assertThat(response.promptTokens()).isEqualTo(3);
        assertThat(response.completionTokens()).isEqualTo(4);
    }

    @Test
    void normalizesMissingNonTextAndProviderFailuresWithoutSensitiveDetails() {
        String prompt = "full prompt apiKey=secret-key";
        assertSafeFailure(new AgentScopeModelProvider(new RecordingModel("model", (ignoredMessages, ignoredOptions)
                -> Flux.just(response(List.of(), new ChatUsage(1, 1, 0)))), "provider"), prompt);

        assertSafeFailure(new AgentScopeModelProvider(new RecordingModel("model", (ignoredMessages, ignoredOptions)
                -> Flux.just(response(List.of(new ContentBlock()), new ChatUsage(1, 1, 0)))), "provider"), prompt);

        assertThatThrownBy(() -> new AgentScopeModelProvider(new RecordingModel("model", (ignoredMessages,
                ignoredOptions) -> {
            throw new IllegalStateException("apiKey=secret-key " + prompt);
        }), "provider").complete(new ModelProvider.ModelRequest(prompt, 8)))
                .isInstanceOfSatisfying(ModelProviderException.class, error -> {
                    assertThat(error.getMessage()).doesNotContain("secret-key", prompt);
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test
    void registryProviderUsesAdmissionAuditAndManagerPriceCatalog() {
        Model model = new RecordingModel("deepseek-chat", (ignoredMessages, ignoredOptions) -> Flux.just(response(
                List.of(text("{\"intent\":\"CREATE_TASK\",\"title\":\"AgentScope\",\"description\":\"ok\"}")),
                new ChatUsage(2_000, 1_000, 0))));
        AgentScopeModelProvider provider = new AgentScopeModelProvider(model, "deepseek");
        ModelProviderRegistry registry = new ModelProviderRegistry("agentscope", null,
                Map.of("agentscope", provider));
        List<ModelCallAudit> audits = new ArrayList<>();
        RecordingAdmission admission = new RecordingAdmission();
        InMemoryModelPriceCatalog prices = new InMemoryModelPriceCatalog(List.of(
                new ModelPrice("deepseek", "deepseek-chat", "USD",
                        new BigDecimal("0.000005"), new BigDecimal("0.000015"))));
        ManagerSessionService service = new ManagerSessionService(registry.defaultProvider(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                        "task:create", false, ignored -> "accepted"))),
                audits::add, java.time.Clock.systemUTC(), admission, new ModelCostCalculator(prices));

        assertThat(service.handleCreateTask("create task",
                new ManagerToolRegistry.ToolContext(Set.of("task:create"), false))).isEqualTo("accepted");
        assertThat(admission.requests).singleElement().satisfies(request -> {
            assertThat(request.provider()).isEqualTo(AgentScopeModelProvider.class.getSimpleName());
            assertThat(request.maxTokens()).isEqualTo(1024);
        });
        assertThat(audits).singleElement().satisfies(audit -> {
            assertThat(audit.provider()).isEqualTo("deepseek");
            assertThat(audit.model()).isEqualTo("deepseek-chat");
            assertThat(audit.tokenUsage()).isEqualTo(new ModelCallAudit.TokenUsage(2_000, 1_000));
            assertThat(audit.costUsd()).isEqualTo(0.025d);
            assertThat(audit.costStatus()).isEqualTo(ModelCallAudit.CostStatus.ESTIMATED);
        });
        assertThat(admission.releases).isEqualTo(1);
    }

    @Test
    void bridgesReloadableManagerProviderAndStopsUsingOldConnectionAfterRotation() {
        RecordingProvider oldProvider = new RecordingProvider("old-key", "old");
        RecordingProvider newProvider = new RecordingProvider("new-key", "new");
        ReloadableModelProvider reloadable = new ReloadableModelProvider(oldProvider);
        Model bridged = ModelProviderAgentScopeModel.from(reloadable);
        AgentScopeModelProvider provider = AgentScopeModelProvider.fromModelProvider(reloadable);

        assertThat(provider.complete(request())).extracting(ModelProvider.ModelResponse::content).isEqualTo("old");
        reloadable.reconnect(() -> newProvider);
        assertThat(provider.complete(request())).extracting(ModelProvider.ModelResponse::content).isEqualTo("new");
        assertThat(oldProvider.calls()).isEqualTo(1);
        assertThat(newProvider.calls()).isEqualTo(1);
        assertThat(bridged.getModelName()).isEqualTo("recorded-model");

        assertThatThrownBy(() -> reloadable.reconnect(() -> {
            throw new IllegalStateException("rotation failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(provider.complete(request()).content()).isEqualTo("new");
        assertThat(newProvider.calls()).isEqualTo(2);
    }

    @Test
    void failedRotationKeepsThePreviousAgentScopeConnection() {
        RecordingProvider oldProvider = new RecordingProvider("old-key", "old");
        ReloadableModelProvider reloadable = new ReloadableModelProvider(oldProvider);
        AgentScopeModelProvider provider = AgentScopeModelProvider.fromModelProvider(reloadable);

        assertThat(provider.complete(request()).content()).isEqualTo("old");
        assertThatThrownBy(() -> reloadable.reconnect(() -> {
            throw new IllegalStateException("new credential unavailable");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(provider.complete(request()).content()).isEqualTo("old");
        assertThat(oldProvider.calls()).isEqualTo(2);
    }

    @Test
    void bridgeNormalizesProviderExceptionWithoutLeakingCredentialOrPrompt() {
        String secret = "new-key";
        String prompt = "private prompt";
        ModelProvider provider = new ModelProvider() {
            @Override
            public ModelResponse complete(ModelRequest request) {
                throw new ModelProviderException("provider secret=" + secret + " prompt=" + prompt,
                        ModelProviderException.Category.AUTHENTICATION);
            }

            @Override
            public String modelName() {
                return "recorded-model";
            }
        };

        assertThatThrownBy(() -> new AgentScopeModelProvider(ModelProviderAgentScopeModel.from(provider), "provider")
                .complete(new ModelProvider.ModelRequest(prompt, 8)))
                .isInstanceOfSatisfying(ModelProviderException.class, error -> {
                    assertThat(error.getMessage()).doesNotContain(secret, prompt);
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test
    void managerAuditForAgentScopeFailureContainsNoCredentialOrPrompt() {
        String secret = "rotated-secret";
        String prompt = "private prompt";
        Model model = new RecordingModel("deepseek-chat", (ignoredMessages, ignoredOptions) -> {
            throw new IllegalStateException("apiKey=" + secret + " prompt=" + prompt);
        });
        AgentScopeModelProvider provider = new AgentScopeModelProvider(model, "deepseek");
        List<ModelCallAudit> audits = new ArrayList<>();
        ManagerSessionService service = new ManagerSessionService(provider,
                new com.fasterxml.jackson.databind.ObjectMapper(), new ManagerToolRegistry(Map.of()),
                audits::add, java.time.Clock.systemUTC());

        assertThatThrownBy(() -> service.handleCreateTask(prompt,
                new ManagerToolRegistry.ToolContext(Set.of(), false)))
                .isInstanceOf(ModelProviderException.class)
                .satisfies(error -> assertThat(error.toString()).doesNotContain(secret, prompt));
        assertThat(audits).singleElement().satisfies(audit -> {
            assertThat(audit.outcome()).isEqualTo(ModelCallAudit.Outcome.FAILURE);
            assertThat(audit.toString()).doesNotContain(secret, prompt);
        });
    }

    private static ModelProvider.ModelRequest request() {
        return new ModelProvider.ModelRequest("hello", 16);
    }

    private static TextBlock text(String value) {
        return TextBlock.builder().text(value).build();
    }

    private static ChatResponse response(List<ContentBlock> content, ChatUsage usage) {
        return ChatResponse.builder().id("response").content(content).usage(usage)
                .metadata(Map.of()).finishReason("stop").build();
    }

    private static void assertSafeFailure(AgentScopeModelProvider provider, String prompt) {
        assertThatThrownBy(() -> provider.complete(new ModelProvider.ModelRequest(prompt, 8)))
                .isInstanceOfSatisfying(ModelProviderException.class, error -> {
                    assertThat(error.getMessage()).doesNotContain("secret-key", prompt);
                    assertThat(error.getCause()).isNull();
                });
    }

    @FunctionalInterface
    private interface ModelCall {
        Flux<ChatResponse> apply(List<io.agentscope.core.message.Msg> messages,
                GenerateOptions options);
    }

    private static final class RecordingModel implements Model {
        private final String modelName;
        private final ModelCall call;

        private RecordingModel(String modelName, ModelCall call) {
            this.modelName = modelName;
            this.call = call;
        }

        @Override
        public Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages,
                List<ToolSchema> tools, GenerateOptions options) {
            return call.apply(messages, options);
        }

        @Override
        public String getModelName() {
            return modelName;
        }
    }

    private static final class RecordingProvider implements ModelProvider {
        private final String secret;
        private final String content;
        private final AtomicInteger calls = new AtomicInteger();

        private RecordingProvider(String secret, String content) {
            this.secret = secret;
            this.content = content;
        }

        @Override
        public ModelResponse complete(ModelRequest request) {
            calls.incrementAndGet();
            return new ModelResponse(content, "recorded-model", 1, 2);
        }

        @Override
        public String providerName() {
            return "recorded";
        }

        @Override
        public String modelName() {
            return "recorded-model";
        }

        private int calls() {
            return calls.get();
        }
    }

    private static final class RecordingAdmission implements ModelCallAdmission {
        private final List<ModelCallAdmissionRequest> requests = new ArrayList<>();
        private int releases;

        @Override
        public ModelCallLease acquire(ModelCallAdmissionRequest request) {
            requests.add(request);
            return ModelCallLease.idempotent(() -> releases++);
        }
    }
}
