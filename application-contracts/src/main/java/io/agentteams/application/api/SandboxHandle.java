package io.agentteams.application.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Provider-neutral reference to a provisioned sandbox.
 */
public record SandboxHandle(
        String providerSandboxId,
        SandboxProfile profile,
        SandboxStatus status,
        String endpointRef,
        Instant expiresAt) {

    public SandboxHandle {
        if (providerSandboxId == null || providerSandboxId.isBlank()) {
            throw new IllegalArgumentException("providerSandboxId must be non-blank");
        }
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (endpointRef != null && endpointRef.isBlank()) {
            throw new IllegalArgumentException("endpointRef must be null or non-blank");
        }
    }
}
