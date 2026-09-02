package io.agentteams.controlplane.task;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A safe, resumable step boundary; checkpointRef never contains prompt or secret material. */
public record TaskRecoveryCheckpoint(UUID id, UUID taskId, UUID runId, UUID attemptId, String stepKey,
        String idempotencyKey, String status, String checkpointRef, Instant createdAt, Instant updatedAt,
        long version) {
    public TaskRecoveryCheckpoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        requireText(stepKey, "stepKey");
        requireText(idempotencyKey, "idempotencyKey");
        requireText(status, "status");
        requireText(checkpointRef, "checkpointRef");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        if (value.length() > 4096) throw new IllegalArgumentException(field + " is too long");
    }
}
