package io.agentteams.runtime;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Sliding-window rate limiter used to shed best-effort event reporting. */
public final class RuntimeEventRateLimiter {
    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final Deque<Long> timestamps = new ArrayDeque<>();

    public RuntimeEventRateLimiter(int limit, Duration window, Clock clock) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
        this.windowMillis = Objects.requireNonNull(window, "window").toMillis();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Returns true when the caller may emit one more event inside the window. */
    public synchronized boolean tryAcquire() {
        long now = clock.millis();
        long windowStart = now - windowMillis;
        while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
            timestamps.pollFirst();
        }
        if (timestamps.size() >= limit) {
            return false;
        }
        timestamps.addLast(now);
        return true;
    }
}
