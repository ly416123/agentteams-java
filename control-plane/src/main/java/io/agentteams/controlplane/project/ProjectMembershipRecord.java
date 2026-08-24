package io.agentteams.controlplane.project;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProjectMembershipRecord(String tenantId, UUID projectId, String subject, ProjectRole role,
        String status, Instant createdAt, Instant updatedAt, long version) {
    public ProjectMembershipRecord {
        requireText(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        requireText(subject, "subject");
        Objects.requireNonNull(role, "role");
        requireText(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    public static ProjectMembershipRecord create(String tenantId, UUID projectId, String subject,
            ProjectRole role, Instant now) {
        return new ProjectMembershipRecord(tenantId, projectId, subject, role, "ACTIVE", now, now, 0);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
