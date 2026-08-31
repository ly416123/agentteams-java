package io.agentteams.controlplane.task;

import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskProcessEvent;
import io.agentteams.controlplane.security.ExecutionContext;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Publishes and replays process facts without introducing a second task state machine. */
@Service
public final class TaskProcessEventService {
    private final TaskProcessEventRepository repository;

    public TaskProcessEventService(TaskProcessEventRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public TaskProcessEvent append(ExecutionContext context, TaskProcessEvent event) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(event, "event");
        repository.insert(context, event);
        return event;
    }

    public List<TaskProcessEvent> replay(ExecutionContext context, UUID taskId, UUID runId, long after,
            Set<TaskEventVisibility> visible, int limit) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(visible, "visible");
        if (after < 0) throw new IllegalArgumentException("after must not be negative");
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        if (visible.isEmpty()) return List.of();
        return List.copyOf(repository.find(context, taskId, runId, after, Set.copyOf(visible), limit));
    }
}
