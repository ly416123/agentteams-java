package io.agentteams.controlplane.audit;

import io.agentteams.application.api.ExecutionEventPort.TaskExecutionCommand;
import java.util.UUID;

/** Durable boundary for runtime model-call usage emitted with terminal task events. */
@FunctionalInterface
public interface ModelCallAuditRecorder {
    void record(UUID taskId, TaskExecutionCommand command);

    static ModelCallAuditRecorder noop() {
        return (taskId, command) -> { };
    }
}
