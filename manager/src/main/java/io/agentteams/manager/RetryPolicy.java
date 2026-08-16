package io.agentteams.manager;

import java.time.Duration;

/** Finite retry budget and bounded exponential backoff for model calls. */
public record RetryPolicy(int maxRetries, Duration initialBackoff, Duration maxBackoff) {
    public static final int MAX_ALLOWED_RETRIES = 5;

    public RetryPolicy {
        if (maxRetries < 0 || maxRetries > MAX_ALLOWED_RETRIES) {
            throw new IllegalArgumentException("maxRetries must be between 0 and " + MAX_ALLOWED_RETRIES);
        }
        if (initialBackoff == null || initialBackoff.isNegative() || initialBackoff.isZero()) {
            throw new IllegalArgumentException("initialBackoff must be positive");
        }
        if (maxBackoff == null || maxBackoff.isNegative() || maxBackoff.isZero()) {
            throw new IllegalArgumentException("maxBackoff must be positive");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be less than initialBackoff");
        }
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(2, Duration.ofMillis(100), Duration.ofSeconds(2));
    }

    /** Retry number is one-based; values beyond the configured budget remain safely capped. */
    public Duration backoffForRetry(int retryNumber) {
        if (retryNumber < 1) throw new IllegalArgumentException("retryNumber must be positive");
        long multiplier = 1L << Math.min(retryNumber - 1, 30);
        Duration candidate;
        try {
            candidate = initialBackoff.multipliedBy(multiplier);
        } catch (ArithmeticException overflow) {
            candidate = maxBackoff;
        }
        return candidate.compareTo(maxBackoff) > 0 ? maxBackoff : candidate;
    }
}
