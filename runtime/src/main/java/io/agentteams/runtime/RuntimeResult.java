package io.agentteams.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RuntimeResult(UUID taskId, boolean success, String output, Instant occurredAt) {
    public RuntimeResult {
        Objects.requireNonNull(taskId, "taskId");
        if (output == null) {
            throw new IllegalArgumentException("output must not be null");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static RuntimeResult success(UUID taskId, String output, Instant occurredAt) {
        return new RuntimeResult(taskId, true, output, occurredAt);
    }

    public static RuntimeResult failure(UUID taskId, String output, Instant occurredAt) {
        return new RuntimeResult(taskId, false, output, occurredAt);
    }
}
