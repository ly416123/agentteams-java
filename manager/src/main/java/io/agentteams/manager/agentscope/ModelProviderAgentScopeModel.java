package io.agentteams.manager.agentscope;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentteams.manager.ModelProvider;
import io.agentteams.manager.ModelProviderException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Flux;

/**
 * AgentScope {@link Model} facade over an existing synchronous Manager
 * provider. It deliberately delegates credential ownership to that provider.
 */
public final class ModelProviderAgentScopeModel implements Model {
    private static final String FAILURE_MESSAGE = "Manager model provider call failed";
    private static final String REQUEST_MESSAGE = "AgentScope model request was invalid";

    private final ModelProvider provider;

    public ModelProviderAgentScopeModel(ModelProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public static ModelProviderAgentScopeModel from(ModelProvider provider) {
        return new ModelProviderAgentScopeModel(provider);
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        try {
            String prompt = extractPrompt(messages);
            int maxTokens = extractMaxTokens(options);
            ModelProvider.ModelResponse response = provider.complete(
                    new ModelProvider.ModelRequest(prompt, maxTokens));
            validateResponse(response);
            ChatUsage usage = toChatUsage(response);
            ChatResponse chatResponse = ChatResponse.builder()
                    .id("manager-model-response")
                    .content(List.<ContentBlock>of(TextBlock.builder().text(response.content()).build()))
                    .usage(usage)
                    .metadata(Map.of())
                    .finishReason("stop")
                    .build();
            return Flux.just(chatResponse);
        } catch (ModelProviderException error) {
            return Flux.error(safeProviderFailure(error));
        } catch (RuntimeException error) {
            return Flux.error(new ModelProviderException(FAILURE_MESSAGE,
                    ModelProviderException.Category.UNKNOWN));
        }
    }

    @Override
    public String getModelName() {
        String modelName = provider.modelName();
        return modelName == null || modelName.isBlank() ? "unknown" : modelName;
    }

    private static String extractPrompt(List<Msg> messages) {
        if (messages == null || messages.size() != 1 || !(messages.get(0) instanceof UserMessage message)
                || message.getContent() == null || message.getContent().isEmpty()) {
            throw new ModelProviderException(REQUEST_MESSAGE, ModelProviderException.Category.PROTOCOL);
        }
        StringBuilder prompt = new StringBuilder();
        for (ContentBlock block : message.getContent()) {
            if (!(block instanceof TextBlock textBlock) || textBlock.getText() == null) {
                throw new ModelProviderException(REQUEST_MESSAGE, ModelProviderException.Category.PROTOCOL);
            }
            prompt.append(textBlock.getText());
        }
        if (prompt.toString().isBlank()) {
            throw new ModelProviderException(REQUEST_MESSAGE, ModelProviderException.Category.PROTOCOL);
        }
        return prompt.toString();
    }

    private static int extractMaxTokens(GenerateOptions options) {
        if (options == null || options.getMaxTokens() == null || options.getMaxTokens() <= 0) {
            throw new ModelProviderException(REQUEST_MESSAGE, ModelProviderException.Category.PROTOCOL);
        }
        return options.getMaxTokens();
    }

    private static void validateResponse(ModelProvider.ModelResponse response) {
        if (response == null || response.content() == null || response.content().isBlank()
                || response.model() == null || response.model().isBlank()
                || response.promptTokens() < 0 || response.completionTokens() < 0) {
            throw new ModelProviderException(FAILURE_MESSAGE, ModelProviderException.Category.PROTOCOL);
        }
    }

    private static ChatUsage toChatUsage(ModelProvider.ModelResponse response) {
        if (response.promptTokens() > Integer.MAX_VALUE || response.completionTokens() > Integer.MAX_VALUE) {
            throw new ModelProviderException(FAILURE_MESSAGE, ModelProviderException.Category.PROTOCOL);
        }
        return new ChatUsage((int) response.promptTokens(), (int) response.completionTokens(), 0);
    }

    private static ModelProviderException safeProviderFailure(ModelProviderException error) {
        return new ModelProviderException(FAILURE_MESSAGE, error.category(), error.retryable(), error.statusCode(), null);
    }
}
