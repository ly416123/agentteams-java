package io.agentteams.controlplane.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 补充要求创建命令（G02 D5）；幂等与事件由持久层在事务内完成。 */
public record CreateTaskAdjustmentCommand(
        UUID adjustmentId,
        UUID taskId,
        String contentJson,
        String actor,
        String source,
        String idempotencyKey,
        String requestHash,
        Instant createdAt) {

    public CreateTaskAdjustmentCommand {
        Objects.requireNonNull(adjustmentId, "adjustmentId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(contentJson, "contentJson");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
