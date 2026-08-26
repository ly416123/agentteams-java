package io.agentteams.application.api;

import java.time.Instant;
import java.util.Objects;

/** Result of an idempotent expiry ensure operation. */
public record SandboxRenewReceipt(
        SandboxProviderRef providerRef,
        SandboxProviderPhase phase,
        Instant expiresAt,
        long observedGeneration) {

    public SandboxRenewReceipt {
        Objects.requireNonNull(providerRef, "providerRef must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (observedGeneration < 0) {
            throw new IllegalArgumentException("observedGeneration must not be negative");
        }
    }
}
