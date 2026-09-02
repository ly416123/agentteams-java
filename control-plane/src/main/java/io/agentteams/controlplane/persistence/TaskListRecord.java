package io.agentteams.controlplane.persistence;

import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Narrow, scope-aware projection for task list responses. */
public record TaskListRecord(UUID id, String title, TaskPhase phase, int priority,
        String tenantId, String projectId, String team, String actor, String source,
        UUID teamId, UUID workerId, Instant createdAt, Instant updatedAt, long version,
        String taskType) {
    public TaskListRecord(UUID id, String title, TaskPhase phase, int priority,
            String tenantId, String projectId, String team, String actor, String source,
            UUID teamId, UUID workerId, Instant createdAt, Instant updatedAt, long version) {
        this(id, title, phase, priority, tenantId, projectId, team, actor, source, teamId, workerId,
                createdAt, updatedAt, version, "NORMAL");
    }
    public TaskListRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }
}
