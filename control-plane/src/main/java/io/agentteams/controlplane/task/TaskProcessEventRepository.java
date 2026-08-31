package io.agentteams.controlplane.task;

import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskProcessEvent;
import io.agentteams.controlplane.security.ExecutionContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Durable store for replayable task-process events. */
public interface TaskProcessEventRepository {
    boolean insert(ExecutionContext context, TaskProcessEvent event);

    List<TaskProcessEvent> find(ExecutionContext context, UUID taskId, UUID runId, long after,
            Set<TaskEventVisibility> visible, int limit);
}
