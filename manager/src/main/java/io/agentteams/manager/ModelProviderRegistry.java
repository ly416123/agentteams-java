package io.agentteams.manager;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime registry for model providers; business services depend on this boundary, not implementations. */
public final class ModelProviderRegistry {
    private final Map<String, ModelProvider> providers = new ConcurrentHashMap<>();
    private final String defaultProvider;
    private final String fallbackProvider;

    public ModelProviderRegistry(String defaultProvider, String fallbackProvider,
            Map<String, ? extends ModelProvider> providers) {
        this.defaultProvider = requireText(defaultProvider, "defaultProvider");
        this.fallbackProvider = fallbackProvider == null || fallbackProvider.isBlank()
                ? null : fallbackProvider.trim();
        Objects.requireNonNull(providers, "providers").forEach(this::register);
        if (!this.providers.containsKey(this.defaultProvider)) {
            throw new IllegalArgumentException("default provider is not registered: " + this.defaultProvider);
        }
        if (this.fallbackProvider != null && !this.providers.containsKey(this.fallbackProvider)) {
            throw new IllegalArgumentException("fallback provider is not registered: " + this.fallbackProvider);
        }
    }

    public void register(String name, ModelProvider provider) {
        providers.put(requireText(name, "name"), Objects.requireNonNull(provider, "provider"));
    }

    public ModelProvider defaultProvider() {
        return resolve(defaultProvider);
    }

    public Optional<ModelProvider> fallbackProvider() {
        return fallbackProvider == null ? Optional.empty() : Optional.of(resolve(fallbackProvider));
    }

    public ModelProvider resolve(String name) {
        String key = requireText(name, "name");
        ModelProvider provider = providers.get(key);
        if (provider == null) {
            throw new IllegalArgumentException("model provider is not registered: " + key);
        }
        return provider;
    }

    public Map<String, ModelProvider> providers() {
        return Map.copyOf(providers);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
