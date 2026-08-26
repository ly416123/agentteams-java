package io.agentteams.application.api;

import java.time.Instant;
import java.util.Objects;

/** Idempotent request to ensure a sandbox expires no earlier than this time. */
public record SandboxRenewCommand(SandboxProviderRef providerRef, Instant expiresAt) {

    public SandboxRenewCommand {
        Objects.requireNonNull(providerRef, "providerRef must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
