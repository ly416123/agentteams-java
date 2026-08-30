package io.agentteams.operator;

import java.util.Optional;
import java.util.UUID;

/** Reads and acknowledges durable lifecycle commands from Control Plane. */
public interface WorkerOperationDirectiveReader {
    Optional<WorkerOperationDirective> active(UUID agentId);

    void confirmTermination(UUID operationId, long expectedVersion);

    static WorkerOperationDirectiveReader noop() {
        return new WorkerOperationDirectiveReader() {
            @Override
            public Optional<WorkerOperationDirective> active(UUID agentId) { return Optional.empty(); }

            @Override
            public void confirmTermination(UUID operationId, long expectedVersion) { }
        };
    }
}
