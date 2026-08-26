package io.agentteams.application.api;

import java.util.Objects;

/** Result of an idempotent termination ensure operation. */
public record SandboxTerminationReceipt(
        SandboxProviderRef providerRef,
        SandboxProviderPhase phase,
        long observedGeneration) {

    public SandboxTerminationReceipt {
        Objects.requireNonNull(providerRef, "providerRef must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        if (observedGeneration < 0) {
            throw new IllegalArgumentException("observedGeneration must not be negative");
        }
    }
}
