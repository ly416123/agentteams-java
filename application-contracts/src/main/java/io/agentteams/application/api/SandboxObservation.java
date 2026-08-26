package io.agentteams.application.api;

import java.time.Instant;
import java.util.Objects;

/** Provider observation used to advance the durable sandbox lifecycle. */
public record SandboxObservation(
        SandboxProviderRef providerRef,
        SandboxProviderPhase phase,
        String endpointRef,
        Instant expiresAt,
        long observedGeneration,
        String workloadUid,
        SandboxFailure failure) {

    public SandboxObservation {
        Objects.requireNonNull(providerRef, "providerRef must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (endpointRef != null && endpointRef.isBlank()) {
            throw new IllegalArgumentException("endpointRef must be null or non-blank");
        }
        if (workloadUid != null && workloadUid.isBlank()) {
            throw new IllegalArgumentException("workloadUid must be null or non-blank");
        }
        if (observedGeneration < 0) {
            throw new IllegalArgumentException("observedGeneration must not be negative");
        }
    }
}
