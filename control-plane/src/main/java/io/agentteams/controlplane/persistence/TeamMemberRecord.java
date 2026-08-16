package io.agentteams.controlplane.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TeamMemberRecord(UUID id, UUID teamId, UUID agentId, String role, String status,
        Instant joinedAt, Instant updatedAt, long version) {
    public TeamMemberRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(agentId, "agentId");
        requireText(role, "role");
        requireText(status, "status");
        Objects.requireNonNull(joinedAt, "joinedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
