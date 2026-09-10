package io.agentteams.controlplane.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 补充要求（G02 D5）：content-only 非破坏性调整；consumed_run_id 标记已并入的执行。 */
public record TaskAdjustmentRecord(
        UUID id,
        UUID taskId,
        String contentJson,
        String actor,
        String source,
        UUID consumedRunId,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public TaskAdjustmentRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(contentJson, "contentJson");
        requireText(actor, "actor");
        requireText(source, "source");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
