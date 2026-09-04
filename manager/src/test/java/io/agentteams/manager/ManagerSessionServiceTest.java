package io.agentteams.manager;

import io.agentteams.application.api.ModelPrice;
import io.agentteams.application.api.ModelPriceCatalog;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ManagerSessionServiceTest {
    @Test
    void validatesModelOutputBeforeCallingCreateTaskTool() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                new ModelProvider.ModelResponse("{\"intent\":\"CREATE_TASK\",\"title\":\"Login\","
                        + "\"description\":\"Implement login\",\"required_capabilities\":[\"java\"],"
                        + "\"priority\":50,\"requires_approval\":false}", "deepseek-chat", 1, 2));
        ManagerSessionService service = new ManagerSessionService(provider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                        "task:create", false, input -> ((CreateTaskIntent) input).title()))));

        assertThat(service.handleCreateTask("create a login task",
                new ManagerToolRegistry.ToolContext(Set.of("task:create"), false))).isEqualTo("Login");
    }

    @Test
    void pricedResponseAuditsProviderResponseTokensAndEstimatedCost() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.providerName()).thenReturn("openai");
        when(provider.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                new ModelProvider.ModelResponse("{\"intent\":\"CREATE_TASK\",\"title\":\"Priced\","
                        + "\"description\":\"cost\"}", "gpt-4o", 2_000, 1_000));
        InMemoryModelPriceCatalog catalog = new InMemoryModelPriceCatalog(List.of(
                new ModelPrice("openai", "gpt-4o", "USD",
                        new BigDecimal("0.000005"), new BigDecimal("0.000015"))));
        List<ModelCallAudit> audits = new ArrayList<>();
        FakeModelCallAdmission admission = new FakeModelCallAdmission();
        ManagerSessionService service = new ManagerSessionService(provider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                        "task:create", false, input -> "accepted"))), audits::add,
                java.time.Clock.systemUTC(), catalog, admission);

        assertThat(service.handleCreateTask("priced", new ManagerToolRegistry.ToolContext(
                Set.of("task:create"), false))).isEqualTo("accepted");

        assertThat(audits).singleElement().satisfies(audit -> {
            assertThat(audit.provider()).isEqualTo("openai");
            assertThat(audit.model()).isEqualTo("gpt-4o");
            assertThat(audit.tokenUsage()).isEqualTo(new ModelCallAudit.TokenUsage(2_000, 1_000));
            assertThat(audit.costUsd()).isEqualTo(0.025d);
            assertThat(audit.costStatus()).isEqualTo(ModelCallAudit.CostStatus.ESTIMATED);
            assertThat(audit.dimensions().toolId()).isEqualTo("create_task");
        });
        assertThat(admission.releases()).isEqualTo(1);
    }

    @Test
    void unpricedResponseIsAuditedAsZeroWithoutBlockingTheCall() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.providerName()).thenReturn("openai");
        when(provider.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                new ModelProvider.ModelResponse("{\"intent\":\"CREATE_TASK\",\"title\":\"Unpriced\","
                        + "\"description\":\"no catalog entry\"}", "gpt-unknown", 4, 6));
        List<ModelCallAudit> audits = new ArrayList<>();
        ManagerSessionService service = new ManagerSessionService(provider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                        "task:create", false, input -> "accepted"))), audits::add,
                java.time.Clock.systemUTC(), new InMemoryModelPriceCatalog());

        assertThat(service.handleCreateTask("unpriced", new ManagerToolRegistry.ToolContext(
                Set.of("task:create"), false))).isEqualTo("accepted");
        assertThat(audits).singleElement().satisfies(audit -> {
            assertThat(audit.costUsd()).isZero();
            assertThat(audit.costStatus()).isEqualTo(ModelCallAudit.CostStatus.UNPRICED);
        });
    }

    @Test
    void invalidOutputCannotTriggerTool() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.complete(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ModelProvider.ModelResponse("not-json", "deepseek-chat", 0, 0));
        ManagerSessionService service = new ManagerSessionService(provider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                        "task:create", false, input -> { throw new AssertionError("must not run"); }))));

        assertThatThrownBy(() -> service.handleCreateTask("bad", new ManagerToolRegistry.ToolContext(
                Set.of("task:create"), false))).isInstanceOf(InvalidModelOutputException.class);
    }

    @Test
    void admitsWithRequestMaxTokensAndReleasesAfterAudit() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.providerName()).thenReturn("qwen");
        when(provider.modelName()).thenReturn("qwen-plus");
        when(provider.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                new ModelProvider.ModelResponse("{\"intent\":\"CREATE_TASK\",\"title\":\"Quota\","
                        + "\"description\":\"admit\"}", "qwen-plus", 1, 2));
        StringBuilder lifecycle = new StringBuilder();
        FakeModelCallAdmission admission = new FakeModelCallAdmission(event -> lifecycle.append(event).append(' '));
        ManagerSessionService service = new ManagerSessionService(provider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                        "task:create", false, input -> "accepted"))),
                audit -> lifecycle.append("audit "), java.time.Clock.systemUTC(), admission);

        service.handleCreateTask("hello", new ManagerToolRegistry.ToolContext(Set.of("task:create"), false));

        assertThat(admission.requests()).singleElement().satisfies(request -> {
            assertThat(request.provider()).isEqualTo("qwen");
            assertThat(request.model()).isEqualTo("qwen-plus");
            assertThat(request.maxTokens()).isEqualTo(1024);
        });
        assertThat(admission.releases()).isEqualTo(1);
        assertThat(lifecycle.toString()).isEqualTo("acquire audit release ");
    }

    @Test
    void propagatesProjectScopeIntoAdmissionRequest() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                new ModelProvider.ModelResponse("{\"intent\":\"CREATE_TASK\",\"title\":\"Scoped\","
                        + "\"description\":\"admit\"}", "qwen-plus", 1, 2));
        FakeModelCallAdmission admission = new FakeModelCallAdmission();
        ManagerSessionService service = new ManagerSessionService(provider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                        "task:create", false, input -> "accepted"))),
                ModelCallAuditor.noop(), java.time.Clock.systemUTC(), admission);

        service.handleCreateTask("scoped", new ManagerToolRegistry.ToolContext(
                Set.of("task:create"), false, "tenant-a", "project-a",
                "worker-a", "task-a", "team-a", "tool-a", "quota-a", "daily_tokens"));

        assertThat(admission.requests()).singleElement().satisfies(request -> {
            assertThat(request.tenantId()).isEqualTo("tenant-a");
            assertThat(request.projectId()).isEqualTo("project-a");
            assertThat(request.dimensions()).isEqualTo(new ModelCallDimensions(
                    "worker-a", "task-a", "team-a", "tool-a", "quota-a", "daily_tokens"));
        });
    }

    @Test
    void admissionRejectionDoesNotCallProvider() {
        ModelProvider provider = mock(ModelProvider.class);
        FakeModelCallAdmission admission = new FakeModelCallAdmission();
        admission.reject();
        ManagerSessionService service = new ManagerSessionService(provider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of()), ModelCallAuditor.noop(), java.time.Clock.systemUTC(), admission);

        assertThatThrownBy(() -> service.handleCreateTask("quota", new ManagerToolRegistry.ToolContext(Set.of(), false)))
                .isInstanceOf(ModelCallAdmissionRejectedException.class);
        org.mockito.Mockito.verify(provider, org.mockito.Mockito.never())
                .complete(org.mockito.ArgumentMatchers.any());
        assertThat(admission.releases()).isZero();
    }

    @Test
    void providerFailureStillAuditsBeforeRelease() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.complete(org.mockito.ArgumentMatchers.any())).thenThrow(new RuntimeException("upstream"));
        StringBuilder lifecycle = new StringBuilder();
        List<ModelCallAudit> audits = new ArrayList<>();
        FakeModelCallAdmission admission = new FakeModelCallAdmission(event -> lifecycle.append(event).append(' '));
        ManagerSessionService service = new ManagerSessionService(provider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of()), audit -> {
                    audits.add(audit);
                    lifecycle.append("audit ");
                },
                java.time.Clock.systemUTC(), admission);

        assertThatThrownBy(() -> service.handleCreateTask("failure", new ManagerToolRegistry.ToolContext(Set.of(), false)))
                .isInstanceOf(RuntimeException.class);
        assertThat(lifecycle.toString()).isEqualTo("acquire audit release ");
        assertThat(admission.releases()).isEqualTo(1);
        assertThat(audits).singleElement().satisfies(audit -> {
            assertThat(audit.costUsd()).isZero();
            assertThat(audit.costStatus()).isEqualTo(ModelCallAudit.CostStatus.NOT_APPLICABLE);
        });
    }

    @Test
    void unsafeProviderIdentityCannotReachAdmissionOrFailureAudit() {
        String secret = "rotated-secret";
        String prompt = "private prompt";
        ModelProvider provider = new ModelProvider() {
            @Override
            public ModelResponse complete(ModelRequest request) {
                throw new ModelProviderException("upstream secret=" + secret + " prompt=" + prompt,
                        ModelProviderException.Category.NETWORK);
            }

            @Override
            public String providerName() {
                return "apiKey=" + secret;
            }

            @Override
            public String modelName() {
                return "prompt=" + prompt;
            }
        };
        List<ModelCallAudit> audits = new ArrayList<>();
        FakeModelCallAdmission admission = new FakeModelCallAdmission();
        ManagerSessionService service = new ManagerSessionService(provider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of()), audits::add, java.time.Clock.systemUTC(), admission);

        assertThatThrownBy(() -> service.handleCreateTask(prompt,
                new ManagerToolRegistry.ToolContext(Set.of(), false)))
                .isInstanceOf(ModelProviderException.class);
        assertThat(admission.requests()).singleElement().satisfies(request -> {
            assertThat(request.provider()).isEqualTo("unknown");
            assertThat(request.model()).isEqualTo("unknown");
        });
        assertThat(audits).singleElement().satisfies(audit -> {
            assertThat(audit.toString()).doesNotContain(secret, prompt);
            assertThat(audit.provider()).isEqualTo("unknown");
            assertThat(audit.model()).isEqualTo("unknown");
        });
    }

    @Test
    void auditFailureStillReleasesLease() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                new ModelProvider.ModelResponse("{\"intent\":\"CREATE_TASK\",\"title\":\"Audit\","
                        + "\"description\":\"ignored\"}", "model", 0, 0));
        FakeModelCallAdmission admission = new FakeModelCallAdmission();
        ManagerSessionService service = new ManagerSessionService(provider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                        "task:create", false, input -> "accepted"))),
                audit -> { throw new RuntimeException("audit unavailable"); }, java.time.Clock.systemUTC(), admission);

        assertThat(service.handleCreateTask("audit failure", new ManagerToolRegistry.ToolContext(
                Set.of("task:create"), false))).isEqualTo("accepted");
        assertThat(admission.releases()).isEqualTo(1);
    }

    @Test
    void idempotentLeaseRunsReleaseOnlyOnce() {
        AtomicBoolean released = new AtomicBoolean();
        int[] calls = {0};
        ModelCallLease lease = ModelCallLease.idempotent(() -> {
            calls[0]++;
            released.set(true);
        });

        lease.close();
        lease.close();

        assertThat(released).isTrue();
        assertThat(calls[0]).isEqualTo(1);
    }
}
