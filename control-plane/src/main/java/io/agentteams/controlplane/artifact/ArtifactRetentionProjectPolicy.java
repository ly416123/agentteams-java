package io.agentteams.controlplane.artifact;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persisted project-level artifact retention policy and its optimistic version. */
public record ArtifactRetentionProjectPolicy(UUID id, String tenantId, String projectId,
        ArtifactRetentionPolicy policy, long version, Instant createdAt, Instant updatedAt) {
    public ArtifactRetentionProjectPolicy {
        Objects.requireNonNull(id, "id");
        requireText(tenantId, "tenantId");
        requireText(projectId, "projectId");
        Objects.requireNonNull(policy, "policy");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
