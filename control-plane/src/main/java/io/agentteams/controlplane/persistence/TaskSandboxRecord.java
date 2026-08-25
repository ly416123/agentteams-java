package io.agentteams.controlplane.persistence;

import io.agentteams.application.api.SandboxProfile;
import io.agentteams.application.api.SandboxStatus;
import io.agentteams.application.api.SandboxTerminationReason;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TaskSandboxRecord(
        UUID id,
        UUID taskId,
        UUID attemptId,
        String idempotencyKey,
        String providerSandboxId,
        SandboxProfile profile,
        SandboxStatus status,
        String template,
        String endpointRef,
        Instant requestedAt,
        Instant expiresAt,
        Instant lastObservedAt,
        Instant terminatedAt,
        SandboxTerminationReason terminationReason,
        String failureCode,
        String redactedFailureMessage,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public TaskSandboxRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(attemptId, "attemptId");
        required(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(status, "status");
        required(template, "template");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if ((terminatedAt == null) != (terminationReason == null)) {
            throw new IllegalArgumentException("terminatedAt and terminationReason must be set together");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
