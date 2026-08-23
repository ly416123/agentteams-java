package io.agentteams.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Idempotent reservation held for one project-scoped runtime model call. */
@FunctionalInterface
public interface RuntimeQuotaLease extends AutoCloseable {
    @Override
    void close();

    static RuntimeQuotaLease noop() {
        return () -> { };
    }

    static RuntimeQuotaLease idempotent(Runnable release) {
        Objects.requireNonNull(release, "release");
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                release.run();
            }
        };
    }
}
