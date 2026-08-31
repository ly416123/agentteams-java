package io.agentteams.controlplane.task;

import io.agentteams.controlplane.security.ExecutionContext;
import java.util.List;
import java.util.UUID;

public interface TaskTreeRepository {
    void upsert(ExecutionContext context, UUID runId, TaskTreeNode node);

    List<TaskTreeNode> find(ExecutionContext context, UUID runId);
}
