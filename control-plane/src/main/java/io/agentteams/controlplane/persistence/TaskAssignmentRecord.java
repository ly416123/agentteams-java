package io.agentteams.controlplane.persistence;

import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TaskAssignmentRecord(
        UUID id,
        UUID taskId,
        UUID attemptId,
        UUID agentId,
        TaskPhase phase,
        Instant assignedAt,
        Instant acceptedAt,
        Instant releasedAt,
        String detailsJson,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public TaskAssignmentRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(assignedAt, "assignedAt");
        Objects.requireNonNull(detailsJson, "detailsJson");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }
}
