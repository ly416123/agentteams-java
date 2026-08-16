package io.agentteams.controlplane.persistence;

import io.agentteams.domain.task.TaskAttempt;
import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TaskAttemptRecord(
        UUID id,
        UUID taskId,
        UUID leaseId,
        TaskPhase phase,
        Instant leaseExpiresAt,
        Instant completedAt,
        String actor,
        String source,
        String failureCode,
        String redactedFailureMessage,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public TaskAttemptRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(leaseId, "leaseId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        requireText(actor, "actor");
        requireText(source, "source");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    public static TaskAttemptRecord fromDomain(TaskAttempt attempt) {
        return new TaskAttemptRecord(attempt.id(), attempt.taskId(), attempt.leaseId(), attempt.phase(),
                attempt.leaseExpiresAt(), attempt.completedAt(), attempt.actor(), attempt.source(),
                attempt.failureCode(), attempt.redactedFailureMessage(), attempt.createdAt(),
                attempt.updatedAt(), attempt.version());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
