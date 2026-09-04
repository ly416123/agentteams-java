package io.agentteams.application.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** A quota reservation held for one model call. */
@FunctionalInterface
public interface QuotaLease extends AutoCloseable {
    @Override
    void close();

    static QuotaLease noop() {
        return () -> { };
    }

    /** Wraps an external release callback so every release path is idempotent. */
    static QuotaLease idempotent(Runnable release) {
        Objects.requireNonNull(release, "release");
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                release.run();
            }
        };
    }
}
