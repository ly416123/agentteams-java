package io.agentteams.application.api;

import java.util.Objects;

/** Result of an idempotent provision ensure operation. */
public record SandboxProvisionReceipt(
        SandboxProviderRef providerRef,
        SandboxProviderPhase phase,
        long observedGeneration) {

    public SandboxProvisionReceipt {
        Objects.requireNonNull(providerRef, "providerRef must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        if (observedGeneration < 0) {
            throw new IllegalArgumentException("observedGeneration must not be negative");
        }
    }
}
