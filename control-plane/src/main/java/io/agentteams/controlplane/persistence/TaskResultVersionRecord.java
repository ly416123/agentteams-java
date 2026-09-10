package io.agentteams.controlplane.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 业务结果版本（G02 D1-D4）：一次「已提交待验收」的交付物。
 * 与 run 级 task_result_manifests（观测投影）职责分离；subtask_id 本轮恒空（D3 预留）。
 */
public record TaskResultVersionRecord(
        UUID id,
        UUID taskId,
        UUID runId,
        UUID manifestId,
        UUID subtaskId,
        int seq,
        String status,
        String summary,
        String contentJson,
        String submittedBy,
        Instant submittedAt,
        String reviewActor,
        String reviewComment,
        Instant reviewedAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REVISION_REQUIRED = "REVISION_REQUIRED";

    public TaskResultVersionRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(summary, "summary");
        requireText(submittedBy, "submittedBy");
        Objects.requireNonNull(submittedAt, "submittedAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (seq <= 0) {
            throw new IllegalArgumentException("seq must be positive");
        }
        if (!isKnownStatus(status)) {
            throw new IllegalArgumentException("unknown result version status: " + status);
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    public boolean submitted() {
        return STATUS_SUBMITTED.equals(status);
    }

    private static boolean isKnownStatus(String value) {
        return STATUS_SUBMITTED.equals(value) || STATUS_ACCEPTED.equals(value)
                || STATUS_REVISION_REQUIRED.equals(value);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
