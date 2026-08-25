package io.agentteams.manager.agentscope;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentteams.manager.ModelProvider;
import io.agentteams.manager.ModelProviderException;
import io.agentteams.manager.ModelIdentity;
import java.util.List;
import java.util.Objects;

/**
 * Synchronous Manager boundary over the AgentScope streaming model contract.
 *
 * <p>This adapter owns no credentials and performs no configuration lookup. A
 * caller supplies an already configured AgentScope {@link Model}; Manager
 * governance remains around the resulting {@link ModelProvider} call.</p>
 */
public final class AgentScopeModelProvider implements ModelProvider {
    private static final String FAILURE_MESSAGE = "AgentScope model call failed";
    private static final String INVALID_RESPONSE_MESSAGE = "AgentScope model response was invalid";
    private static final String DEFAULT_PROVIDER_NAME = "agentscope";

    private final Model model;
    private final String providerName;

    public AgentScopeModelProvider(Model model, String providerName) {
        this.model = Objects.requireNonNull(model, "model");
        this.providerName = ModelIdentity.normalize(providerName, DEFAULT_PROVIDER_NAME);
    }

    public AgentScopeModelProvider(Model model) {
        this(model, "agentscope");
    }

    /** Bridges an existing Manager provider without reading its credentials again. */
    public static AgentScopeModelProvider fromModelProvider(ModelProvider provider) {
        Objects.requireNonNull(provider, "provider");
        return new AgentScopeModelProvider(ModelProviderAgentScopeModel.from(provider),
                ModelIdentity.read(provider::providerName, DEFAULT_PROVIDER_NAME));
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        if (request == null) {
            throw new ModelProviderException("AgentScope model request was invalid",
                    ModelProviderException.Category.PROTOCOL);
        }
        try {
            String modelName = safeModelName();
            GenerateOptions options = GenerateOptions.builder()
                    .modelName(modelName)
                    .maxTokens(request.maxTokens())
                    .stream(true)
                    .build();
            var responses = model.stream(List.of(new UserMessage(request.prompt())), List.<ToolSchema>of(), options)
                    .collectList().block();
            return toModelResponse(responses);
        } catch (ModelProviderException error) {
            throw safeProviderFailure(error);
        } catch (RuntimeException error) {
            throw new ModelProviderException(FAILURE_MESSAGE, ModelProviderException.Category.UNKNOWN);
        }
    }

    @Override
    public String providerName() {
        return providerName;
    }

    @Override
    public String modelName() {
        try {
            return safeModelName();
        } catch (ModelProviderException error) {
            return "unknown";
        }
    }

    private ModelResponse toModelResponse(List<ChatResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            throw protocolFailure();
        }
        StringBuilder content = new StringBuilder();
        ChatUsage usage = null;
        for (ChatResponse response : responses) {
            if (response == null || response.getContent() == null) {
                throw protocolFailure();
            }
            for (ContentBlock block : response.getContent()) {
                if (!(block instanceof TextBlock textBlock) || textBlock.getText() == null) {
                    throw protocolFailure();
                }
                content.append(textBlock.getText());
            }
            if (response.getUsage() != null) {
                usage = response.getUsage();
            }
        }
        if (content.toString().isBlank() || usage == null) {
            throw protocolFailure();
        }
        long inputTokens = usage.getInputTokens();
        long outputTokens = usage.getOutputTokens();
        if (inputTokens < 0 || inputTokens > Integer.MAX_VALUE
                || outputTokens < 0 || outputTokens > Integer.MAX_VALUE) {
            throw protocolFailure();
        }
        return new ModelResponse(content.toString(), safeModelName(), inputTokens, outputTokens);
    }

    private String safeModelName() {
        return ModelIdentity.read(model::getModelName, "unknown");
    }

    private static ModelProviderException protocolFailure() {
        return new ModelProviderException(INVALID_RESPONSE_MESSAGE,
                ModelProviderException.Category.PROTOCOL);
    }

    private static ModelProviderException safeProviderFailure(ModelProviderException error) {
        return new ModelProviderException(FAILURE_MESSAGE, error.category(), error.retryable(), error.statusCode(), null);
    }

}
