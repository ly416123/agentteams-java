package io.agentteams.controlplane.task;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One projected node in a task run's decomposition graph. */
public record TaskTreeNode(UUID taskId, UUID parentTaskId, long sequence, String status,
        List<UUID> dependencyIds, Instant updatedAt, Map<UUID, String> dependencyResultRefs) {
    public TaskTreeNode {
        Objects.requireNonNull(taskId, "taskId");
        status = required(status, "status");
        dependencyIds = List.copyOf(Objects.requireNonNull(dependencyIds, "dependencyIds"));
        Objects.requireNonNull(updatedAt, "updatedAt");
        dependencyResultRefs = Map.copyOf(Objects.requireNonNull(dependencyResultRefs, "dependencyResultRefs"));
        if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
        if (dependencyIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("dependencyIds must not contain null");
        }
    }

    public TaskTreeNode(UUID taskId, UUID parentTaskId, long sequence, String status,
            List<UUID> dependencyIds, Instant updatedAt) {
        this(taskId, parentTaskId, sequence, status, dependencyIds, updatedAt, Map.of());
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
