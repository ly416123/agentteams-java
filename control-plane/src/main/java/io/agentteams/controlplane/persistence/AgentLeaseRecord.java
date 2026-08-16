package io.agentteams.controlplane.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AgentLeaseRecord(
        UUID id,
        UUID agentId,
        UUID taskAttemptId,
        Instant acquiredAt,
        Instant expiresAt,
        Instant releasedAt,
        String status,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public AgentLeaseRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(taskAttemptId, "taskAttemptId");
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        requireText(status, "status");
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
