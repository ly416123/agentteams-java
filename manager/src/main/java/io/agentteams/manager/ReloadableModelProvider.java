package io.agentteams.manager;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Stable Manager-facing provider handle which can atomically adopt a provider
 * rebuilt after a credential or endpoint rotation.
 *
 * <p>The replacement is constructed before the active reference is changed.
 * If secret resolution, connection construction, or validation fails, the
 * previous provider remains usable. The wrapper does not retain or expose
 * credential material; callers own the supplier and provider construction.</p>
 */
public final class ReloadableModelProvider implements ModelProvider {
    private final AtomicReference<ModelProvider> delegate;
    private final AtomicLong connectionGeneration = new AtomicLong(1);

    public ReloadableModelProvider(ModelProvider initialProvider) {
        this.delegate = new AtomicReference<>(Objects.requireNonNull(initialProvider, "initialProvider"));
    }

    /**
     * Builds and activates a replacement provider. The supplier is invoked
     * before the active provider changes, so a failed rotation is fail-safe.
     */
    public void reconnect(Supplier<? extends ModelProvider> replacementFactory) {
        Objects.requireNonNull(replacementFactory, "replacementFactory");
        ModelProvider replacement = Objects.requireNonNull(replacementFactory.get(), "replacementProvider");
        delegate.set(replacement);
        connectionGeneration.incrementAndGet();
    }

    /** Monotonically increasing connection identity, useful for diagnostics and tests. */
    public long connectionGeneration() {
        return connectionGeneration.get();
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        return delegate.get().complete(request);
    }

    @Override
    public String providerName() {
        return delegate.get().providerName();
    }

    @Override
    public String modelName() {
        return delegate.get().modelName();
    }
}
