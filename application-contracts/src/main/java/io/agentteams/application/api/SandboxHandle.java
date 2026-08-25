package io.agentteams.application.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Provider-neutral reference to a provisioned sandbox.
 */
public record SandboxHandle(
        String providerSandboxId,
        SandboxProfile profile,
        SandboxStatus status,
        String endpointRef,
        Instant expiresAt,
        UUID taskId,
        UUID attemptId) {

    public SandboxHandle(String providerSandboxId, SandboxProfile profile, SandboxStatus status,
            String endpointRef, Instant expiresAt) {
        this(providerSandboxId, profile, status, endpointRef, expiresAt, null, null);
    }

    public SandboxHandle {
        if (providerSandboxId == null || providerSandboxId.isBlank()) {
            throw new IllegalArgumentException("providerSandboxId must be non-blank");
        }
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if ((taskId == null) != (attemptId == null)) {
            throw new IllegalArgumentException("taskId and attemptId owners must be supplied together");
        }
        if (endpointRef != null && endpointRef.isBlank()) {
            throw new IllegalArgumentException("endpointRef must be null or non-blank");
        }
    }

    /** Binds the provider reference to the control-plane task attempt exactly once. */
    public SandboxHandle withOwner(UUID ownerTaskId, UUID ownerAttemptId) {
        Objects.requireNonNull(ownerTaskId, "ownerTaskId must not be null");
        Objects.requireNonNull(ownerAttemptId, "ownerAttemptId must not be null");
        if (taskId != null && (!taskId.equals(ownerTaskId) || !attemptId.equals(ownerAttemptId))) {
            throw new IllegalArgumentException("sandbox handle owner cannot be changed");
        }
        return taskId == null ? new SandboxHandle(providerSandboxId, profile, status, endpointRef, expiresAt,
                ownerTaskId, ownerAttemptId) : this;
    }
}
