package io.agentteams.controlplane.matrix;

import java.time.Duration;
import java.util.Optional;

/** A sanitized Matrix delivery failure with an explicit retry policy. */
public final class MatrixDeliveryException extends RuntimeException {
    private final boolean retryable;
    private final Duration retryAfter;

    public MatrixDeliveryException(String message, boolean retryable, Duration retryAfter) {
        super(message);
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public boolean retryable() { return retryable; }
    public Optional<Duration> retryAfter() { return Optional.ofNullable(retryAfter); }
}
