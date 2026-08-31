package io.agentteams.controlplane.task;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persisted view of an open or resolved consistency finding. */
public record TaskStateConsistencyIssueRecord(UUID id, UUID taskId, UUID runId, String organizationId,
        String tenantId, String type, String taskPhase, String runStatus, String manifestStatus, String detail,
        String status, int occurrences, Instant firstSeenAt, Instant lastSeenAt, Instant resolvedAt) {
    public TaskStateConsistencyIssueRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        requireText(organizationId, "organizationId");
        requireText(tenantId, "tenantId");
        requireText(type, "type");
        requireText(taskPhase, "taskPhase");
        requireText(runStatus, "runStatus");
        if (manifestStatus != null && manifestStatus.isBlank()) {
            throw new IllegalArgumentException("manifestStatus must not be blank");
        }
        requireText(detail, "detail");
        requireText(status, "status");
        if (occurrences < 1) throw new IllegalArgumentException("occurrences must be positive");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
