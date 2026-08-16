package io.agentteams.manager;

public interface ModelProvider {
    ModelResponse complete(ModelRequest request);

    /** Stable provider identity used by Manager audit records. */
    default String providerName() { return getClass().getSimpleName(); }

    /** Configured model identity when a call fails before a response is available. */
    default String modelName() { return "unknown"; }

    record ModelRequest(String prompt, int maxTokens) {
        public ModelRequest {
            if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("prompt must not be blank");
            if (maxTokens <= 0) throw new IllegalArgumentException("maxTokens must be positive");
        }
    }

    record ModelResponse(String content, String model, long promptTokens, long completionTokens) {
        public ModelResponse {
            if (content == null || content.isBlank()) throw new IllegalArgumentException("content must not be blank");
            if (model == null || model.isBlank()) throw new IllegalArgumentException("model must not be blank");
        }
    }
}
