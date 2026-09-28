package io.agentteams.controlplane.evaluation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EvaluationRunRecord(UUID id, UUID suiteId, UUID caseId, UUID taskId, UUID executionRunId,
        String evaluatorVersion, Status status, Instant startedAt, Instant completedAt, Instant createdAt) {
    public enum Status { QUEUED, RUNNING, PASSED, FAILED, INCONCLUSIVE }

    public EvaluationRunRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(suiteId, "suiteId");
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(executionRunId, "executionRunId");
        evaluatorVersion = required(evaluatorVersion, "evaluatorVersion");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (completedAt != null && startedAt == null) {
            throw new IllegalArgumentException("completedAt requires startedAt");
        }
        if (startedAt != null && completedAt != null && completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not precede startedAt");
        }
    }

    public static EvaluationRunRecord queued(UUID id, UUID suiteId, UUID caseId, UUID taskId,
            UUID executionRunId, String evaluatorVersion, Instant createdAt) {
        return new EvaluationRunRecord(id, suiteId, caseId, taskId, executionRunId, evaluatorVersion,
                Status.QUEUED, null, null, createdAt);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
