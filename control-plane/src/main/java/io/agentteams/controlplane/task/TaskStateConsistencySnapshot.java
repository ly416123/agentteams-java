package io.agentteams.controlplane.task;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable cross-table facts used by the consistency checker. */
public record TaskStateConsistencySnapshot(UUID taskId, UUID runId, String organizationId, String tenantId,
        String taskPhase, String runStatus, String manifestStatus, int activeAttemptCount, int activeLeaseCount,
        long processEventCount, long maxProcessSequence, long unfinishedSubtaskCount, Instant observedAt) {
    public TaskStateConsistencySnapshot {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        requireText(organizationId, "organizationId");
        requireText(tenantId, "tenantId");
        requireText(taskPhase, "taskPhase");
        requireText(runStatus, "runStatus");
        if (manifestStatus != null && manifestStatus.isBlank()) {
            throw new IllegalArgumentException("manifestStatus must not be blank");
        }
        if (activeAttemptCount < 0 || activeLeaseCount < 0 || processEventCount < 0
                || unfinishedSubtaskCount < 0 || maxProcessSequence < -1) {
            throw new IllegalArgumentException("consistency counts must not be negative");
        }
        if (processEventCount == 0 && maxProcessSequence != -1) {
            throw new IllegalArgumentException("empty process events must have max sequence -1");
        }
        Objects.requireNonNull(observedAt, "observedAt");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
