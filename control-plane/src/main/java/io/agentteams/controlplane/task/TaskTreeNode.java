package io.agentteams.controlplane.task;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One projected node in a task run's decomposition graph. */
public record TaskTreeNode(UUID taskId, UUID parentTaskId, long sequence, String status,
        List<UUID> dependencyIds, Instant updatedAt) {
    public TaskTreeNode {
        Objects.requireNonNull(taskId, "taskId");
        status = required(status, "status");
        dependencyIds = List.copyOf(Objects.requireNonNull(dependencyIds, "dependencyIds"));
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
        if (dependencyIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("dependencyIds must not contain null");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
