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
        long version,
        String provider,
        String providerResourceId,
        String providerResourceUid,
        long observedGeneration,
        String workloadUid,
        String desiredState,
        String operationOwner,
        Instant operationExpiresAt,
        String operationKind,
        int retryCount,
        Instant nextAttemptAt,
        Instant lastDispatchedAt,
        UUID dispatchEventId,
        String detailsJson) {

    public TaskSandboxRecord(UUID id, UUID taskId, UUID attemptId, String idempotencyKey,
            String providerSandboxId, SandboxProfile profile, SandboxStatus status, String template,
            String endpointRef, Instant requestedAt, Instant expiresAt, Instant lastObservedAt,
            Instant terminatedAt, SandboxTerminationReason terminationReason, String failureCode,
            String redactedFailureMessage, Instant createdAt, Instant updatedAt, long version) {
        this(id, taskId, attemptId, idempotencyKey, providerSandboxId, profile, status, template, endpointRef,
                requestedAt, expiresAt, lastObservedAt, terminatedAt, terminationReason, failureCode,
                redactedFailureMessage, createdAt, updatedAt, version, "fake", providerSandboxId, null, 0,
                null, "ACTIVE", null, null, null, 0, createdAt, null, null, "{}");
    }

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
        required(provider, "provider");
        required(desiredState, "desiredState");
        required(detailsJson, "detailsJson");
        if ((terminatedAt == null) != (terminationReason == null)) {
            throw new IllegalArgumentException("terminatedAt and terminationReason must be set together");
        }
        if (version < 0 || observedGeneration < 0 || retryCount < 0) throw new IllegalArgumentException(
                "version, observedGeneration and retryCount must not be negative");
        if (!desiredState.equals("ACTIVE") && !desiredState.equals("TERMINATED")) {
            throw new IllegalArgumentException("desiredState must be ACTIVE or TERMINATED");
        }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
