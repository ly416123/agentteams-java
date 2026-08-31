package io.agentteams.controlplane.task;

import io.agentteams.controlplane.security.ExecutionContext;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Maintains the task decomposition projection without owning task lifecycle state. */
@Service
public final class TaskTreeService {
    private final TaskTreeRepository repository;

    public TaskTreeService(TaskTreeRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public TaskTreeNode upsert(ExecutionContext context, UUID runId, TaskTreeNode node) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(node, "node");
        if (node.taskId().equals(node.parentTaskId()) || node.dependencyIds().contains(node.taskId())) {
            throw new IllegalArgumentException("task graph cannot contain a self reference");
        }
        repository.upsert(context, runId, node);
        return node;
    }

    public List<TaskTreeNode> find(ExecutionContext context, UUID runId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(runId, "runId");
        return List.copyOf(repository.find(context, runId));
    }
}
