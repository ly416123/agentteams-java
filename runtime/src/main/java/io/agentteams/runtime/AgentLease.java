package io.agentteams.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AgentLease(UUID taskId, String attemptId, String leaseId, Instant expiresAt, long expectedVersion) {
    public AgentLease(UUID taskId, String attemptId, String leaseId, Instant expiresAt) {
        this(taskId, attemptId, leaseId, expiresAt, 0);
    }

    public AgentLease {
        Objects.requireNonNull(taskId, "taskId");
        if (attemptId == null || attemptId.isBlank()) {
            throw new IllegalArgumentException("attemptId must not be blank");
        }
        if (leaseId == null || leaseId.isBlank()) {
            throw new IllegalArgumentException("leaseId must not be blank");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }
}
