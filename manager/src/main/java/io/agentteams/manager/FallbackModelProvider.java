package io.agentteams.manager;

import java.util.Objects;

/** Routes retryable primary-provider failures to a configured fallback provider. */
public final class FallbackModelProvider implements ModelProvider {
    private final ModelProvider primary;
    private final ModelProvider fallback;

    public FallbackModelProvider(ModelProvider primary, ModelProvider fallback) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        try {
            return primary.complete(request);
        } catch (ModelProviderException error) {
            if (!error.retryable()) {
                throw error;
            }
            return fallback.complete(request);
        }
    }

    @Override
    public String providerName() {
        return primary.providerName() + "->" + fallback.providerName();
    }

    @Override
    public String modelName() {
        return primary.modelName();
    }
}
