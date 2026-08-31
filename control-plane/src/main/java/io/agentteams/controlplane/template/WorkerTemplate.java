package io.agentteams.controlplane.template;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record WorkerTemplate(UUID id, String tenantId, String projectId, String name, String displayName,
        Long currentPublishedRevision, long version, Instant createdAt, Instant updatedAt, long revisionVersion) {
    public WorkerTemplate {
        Objects.requireNonNull(id, "id");
        requireText(tenantId, "tenantId");
        requireText(projectId, "projectId");
        requireText(name, "name");
        requireText(displayName, "displayName");
        if (currentPublishedRevision != null && currentPublishedRevision < 1) {
            throw new IllegalArgumentException("currentPublishedRevision must be positive");
        }
        if (version < 0 || revisionVersion < 0) throw new IllegalArgumentException("version must not be negative");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        tenantId = tenantId.trim(); projectId = projectId.trim(); name = name.trim(); displayName = displayName.trim();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
