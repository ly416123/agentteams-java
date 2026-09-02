package io.agentteams.controlplane.task;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Durable recovery policy state for one task; it contains no prompt or secret payload. */
public record TaskRecoveryState(UUID taskId, int recoveryCount, int maxRecoveryAttempts, String status,
        String lastReason, Instant nextAttemptAt, Instant lastRecoveredAt, Instant createdAt, Instant updatedAt,
        long version) {
    private static final Set<String> STATUSES = Set.of("READY", "RECOVERY_REQUIRED");

    public TaskRecoveryState {
        Objects.requireNonNull(taskId, "taskId");
        if (recoveryCount < 0) throw new IllegalArgumentException("recoveryCount must not be negative");
        if (maxRecoveryAttempts <= 0) throw new IllegalArgumentException("maxRecoveryAttempts must be positive");
        if (status == null || !STATUSES.contains(status)) throw new IllegalArgumentException("unsupported status");
        if (lastReason != null && lastReason.length() > 256) throw new IllegalArgumentException("lastReason too long");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }
}
