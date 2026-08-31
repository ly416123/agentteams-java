package io.agentteams.controlplane.task;

import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.controlplane.security.ExecutionContext;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Publishes safe decision records and applies visibility at query time. */
@Service
public final class TaskDecisionRecordService {
    private final TaskDecisionRecordRepository repository;

    public TaskDecisionRecordService(TaskDecisionRecordRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public TaskDecisionRecord append(ExecutionContext context, TaskDecisionRecord record) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(record, "record");
        repository.insert(context, record);
        return record;
    }

    public List<TaskDecisionRecord> find(ExecutionContext context, UUID taskId, UUID runId,
            Set<TaskEventVisibility> visible) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(visible, "visible");
        if (visible.isEmpty()) return List.of();
        return List.copyOf(repository.find(context, taskId, runId, Set.copyOf(visible)));
    }
}
