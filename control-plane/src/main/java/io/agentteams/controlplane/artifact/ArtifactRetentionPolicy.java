package io.agentteams.controlplane.artifact;

import java.time.Duration;
import java.util.Objects;

/** Immutable retention policy for task artifacts and temporary uploads. */
public record ArtifactRetentionPolicy(
        Duration successfulTaskRetention,
        Duration failedTaskRetention,
        Duration temporaryUploadRetention,
        boolean legalHold) {

    public ArtifactRetentionPolicy {
        nonNegative(successfulTaskRetention, "successfulTaskRetention");
        nonNegative(failedTaskRetention, "failedTaskRetention");
        nonNegative(temporaryUploadRetention, "temporaryUploadRetention");
    }

    public long successfulTaskRetentionSeconds() {
        return successfulTaskRetention.toSeconds();
    }

    public long failedTaskRetentionSeconds() {
        return failedTaskRetention.toSeconds();
    }

    public long temporaryUploadRetentionSeconds() {
        return temporaryUploadRetention.toSeconds();
    }

    private static void nonNegative(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isNegative()) throw new IllegalArgumentException(field + " must not be negative");
        try {
            value.toSeconds();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(field + " is too large", error);
        }
    }
}
