package io.agentteams.controlplane.task;

import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.controlplane.security.ExecutionContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface TaskDecisionRecordRepository {
    void insert(ExecutionContext context, TaskDecisionRecord record);

    List<TaskDecisionRecord> find(ExecutionContext context, UUID taskId, UUID runId, Set<TaskEventVisibility> visible);
}
