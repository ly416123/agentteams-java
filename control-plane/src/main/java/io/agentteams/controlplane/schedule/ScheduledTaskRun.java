package io.agentteams.controlplane.schedule;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One durable occurrence of a scheduled task, linked to its ordinary Task. */
public record ScheduledTaskRun(UUID id, UUID scheduleId, UUID taskId, UUID executionRunId,
        Instant occurrenceAt, Status status, String taskPhase, String resultStatus, String resultSummary,
        Instant createdAt, Instant updatedAt, long version) {
    public enum Status { TRIGGERED, RUNNING, SUCCEEDED, FAILED, CANCELLED, RECOVERY_REQUIRED }

    public ScheduledTaskRun(UUID id, UUID scheduleId, UUID taskId, Instant occurrenceAt,
            Status status, Instant updatedAt, long version) {
        this(id, scheduleId, taskId, null, occurrenceAt, status, null, null, null,
                updatedAt, updatedAt, version);
    }

    public ScheduledTaskRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scheduleId, "scheduleId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(occurrenceAt, "occurrenceAt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    public boolean active() {
        return status == Status.TRIGGERED || status == Status.RUNNING || status == Status.RECOVERY_REQUIRED;
    }

    public ScheduledTaskRun withStatus(Status next, Instant at) {
        return new ScheduledTaskRun(id, scheduleId, taskId, executionRunId, occurrenceAt, next,
                taskPhase, resultStatus, resultSummary, createdAt, at, version + 1);
    }
}
