package io.agentteams.controlplane.project;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persisted invitation metadata; the clear-text token is never part of this record. */
public record ProjectInvitationRecord(UUID id, String tenantId, UUID projectId, String subject,
        ProjectRole role, String tokenHash, Instant expiresAt, String createdBy, Instant createdAt,
        Status status, Instant acceptedAt) {
    public ProjectInvitationRecord {
        Objects.requireNonNull(id, "id");
        requireText(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        requireText(subject, "subject");
        Objects.requireNonNull(role, "role");
        requireText(tokenHash, "tokenHash");
        Objects.requireNonNull(expiresAt, "expiresAt");
        requireText(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(status, "status");
    }

    public static ProjectInvitationRecord invited(UUID id, String tenantId, UUID projectId,
            String subject, ProjectRole role, String tokenHash, Instant expiresAt, String createdBy,
            Instant createdAt) {
        return new ProjectInvitationRecord(id, tenantId, projectId, subject, role, tokenHash,
                expiresAt, createdBy, createdAt, Status.INVITED, null);
    }

    public enum Status {
        INVITED,
        ACCEPTED,
        EXPIRED,
        REVOKED
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
