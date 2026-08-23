package io.agentteams.manager;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A reservation held for the duration of one admitted model call.
 *
 * <p>{@link #close()} is the release operation. Implementations must make it
 * idempotent: the first close releases the reservation and later closes are
 * no-ops. {@link #idempotent(Runnable)} is provided so adapters do not need to
 * duplicate that guard.</p>
 */
@FunctionalInterface
public interface ModelCallLease extends AutoCloseable {
    @Override
    void close();

    static ModelCallLease noop() {
        return () -> { };
    }

    /** Wraps a release callback with the port's required close idempotency. */
    static ModelCallLease idempotent(Runnable release) {
        Objects.requireNonNull(release, "release");
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                release.run();
            }
        };
    }
}
