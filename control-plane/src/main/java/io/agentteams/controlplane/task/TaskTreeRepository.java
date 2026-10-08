package io.agentteams.controlplane.task;

import io.agentteams.controlplane.security.ExecutionContext;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TaskTreeRepository {
    void upsert(ExecutionContext context, UUID runId, TaskTreeNode node);

    List<TaskTreeNode> find(ExecutionContext context, UUID runId);

    /**
     * Reads the latest task tree projection that contains children for a parent task.
     * A later aggregation run may not write task_subtasks rows, so callers must not
     * assume that the latest run id also owns the latest child projection.
     */
    default List<TaskTreeNode> findLatestForParent(ExecutionContext context, UUID parentTaskId) {
        return List.of();
    }

    /** 声明式同步：删除 run 下不在 keepTaskIds 中的子任务投影行，返回删除行数。 */
    int deleteOthers(ExecutionContext context, UUID runId, Collection<UUID> keepTaskIds);
}
