package io.agentteams.application.api;

import java.util.Objects;

/** Idempotent request to terminate a sandbox. */
public record SandboxTerminationCommand(
        SandboxProviderRef providerRef,
        SandboxTerminationReason reason) {

    public SandboxTerminationCommand {
        Objects.requireNonNull(providerRef, "providerRef must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
