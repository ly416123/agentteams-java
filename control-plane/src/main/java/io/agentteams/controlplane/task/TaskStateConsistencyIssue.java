package io.agentteams.controlplane.task;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A stable, sanitized consistency finding for one Task Run. */
public record TaskStateConsistencyIssue(UUID taskId, UUID runId, String organizationId, String tenantId,
        String type, String taskPhase, String runStatus, String manifestStatus, String detail, Instant observedAt) {
    public TaskStateConsistencyIssue {
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
        Objects.requireNonNull(observedAt, "observedAt");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
