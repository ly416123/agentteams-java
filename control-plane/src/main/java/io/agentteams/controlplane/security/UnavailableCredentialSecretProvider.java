package io.agentteams.controlplane.security;

import java.util.Optional;

/** Fail-closed default until a deployment-owned production secret adapter is configured. */
public final class UnavailableCredentialSecretProvider implements CredentialSecretProvider {
    @Override
    public Optional<String> resolve(String credentialRef) {
        return Optional.empty();
    }
}
