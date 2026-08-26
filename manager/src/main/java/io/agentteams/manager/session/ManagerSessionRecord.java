package io.agentteams.manager.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ManagerSessionRecord(UUID id, String tenantId, String projectId, String actor,
        Status status, long version, Instant createdAt, Instant updatedAt) {
    public enum Status { ACTIVE, CANCELLED }

    public ManagerSessionRecord {
        Objects.requireNonNull(id, "id");
        requireText(tenantId, "tenantId");
        requireText(projectId, "projectId");
        requireText(actor, "actor");
        Objects.requireNonNull(status, "status");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static ManagerSessionRecord newSession(UUID id, String tenantId, String projectId,
            String actor, Instant now) {
        return new ManagerSessionRecord(id, tenantId, projectId, actor, Status.ACTIVE, 0, now, now);
    }

    public ManagerSessionRecord withStatus(Status nextStatus, Instant at) {
        return new ManagerSessionRecord(id, tenantId, projectId, actor, nextStatus, version + 1,
                createdAt, at);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
