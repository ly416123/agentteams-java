package io.agentteams.controlplane.task;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TaskRecoveryStateRepository {
    Optional<TaskRecoveryState> findByTaskId(UUID taskId);

    /** Records one lease-expiry recovery inside the caller's transaction. */
    TaskRecoveryState recordLeaseExpiry(UUID taskId, Instant at, String reason);

    /** Starts a fresh automatic-recovery budget after an explicit operator retry. */
    void resetForManualRetry(UUID taskId, Instant at);
}
