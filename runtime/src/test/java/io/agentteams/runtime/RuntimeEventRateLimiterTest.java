package io.agentteams.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RuntimeEventRateLimiterTest {

    @Test
    void allowsUpToLimitInsideWindow() {
        MutableClock clock = new MutableClock();
        RuntimeEventRateLimiter limiter = new RuntimeEventRateLimiter(3, Duration.ofSeconds(1), clock);
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void freesSlotsWhenWindowSlides() {
        MutableClock clock = new MutableClock();
        RuntimeEventRateLimiter limiter = new RuntimeEventRateLimiter(1, Duration.ofSeconds(1), clock);
        assertTrue(limiter.tryAcquire());
        clock.advanceMillis(1001);
        assertTrue(limiter.tryAcquire());
    }

    /** 手动推进的测试时钟，避免 Thread.sleep。 */
    static final class MutableClock extends Clock {
        private long millis;

        void advanceMillis(long delta) {
            millis += delta;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }
}
