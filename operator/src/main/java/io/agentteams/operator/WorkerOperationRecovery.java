package io.agentteams.operator;

import java.util.Optional;
import java.util.UUID;

/** Recovery seam for restoring a Worker after a failed rollout. */
public interface WorkerOperationRecovery {
    Optional<FailedWorkerOperation> failed(UUID agentId);

    void rollback(UUID operationId, long expectedVersion);

    static WorkerOperationRecovery noop() {
        return new WorkerOperationRecovery() {
            @Override
            public Optional<FailedWorkerOperation> failed(UUID agentId) { return Optional.empty(); }

            @Override
            public void rollback(UUID operationId, long expectedVersion) { }
        };
    }

    record FailedWorkerOperation(UUID id, UUID agentId, String previousStableSpec, long version) { }
}
