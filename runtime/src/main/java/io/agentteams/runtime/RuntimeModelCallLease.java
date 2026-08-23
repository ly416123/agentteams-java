package io.agentteams.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Idempotent reservation held until a runtime model call reaches a terminal state. */
@FunctionalInterface
public interface RuntimeModelCallLease extends AutoCloseable {
    @Override
    void close();

    static RuntimeModelCallLease noop() {
        return () -> { };
    }

    static RuntimeModelCallLease idempotent(Runnable release) {
        Objects.requireNonNull(release, "release");
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                release.run();
            }
        };
    }
}
