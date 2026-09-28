package io.agentteams.controlplane.conversation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable control-plane conversation scoped to a tenant resource. */
public record ConversationRecord(UUID id, String tenantId, String projectId, String teamId,
        String title, String status, String createdBy, Instant createdAt, Instant updatedAt, long version) {
    public ConversationRecord {
        Objects.requireNonNull(id, "id");
        requireText(tenantId, "tenantId");
        requireText(projectId, "projectId");
        if (teamId != null && teamId.isBlank()) {
            throw new IllegalArgumentException("teamId must not be blank when supplied");
        }
        requireText(title, "title");
        if (!"ACTIVE".equals(status) && !"ARCHIVED".equals(status)) {
            throw new IllegalArgumentException("status must be ACTIVE or ARCHIVED");
        }
        requireText(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
