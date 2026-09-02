package io.agentteams.controlplane.security;

import java.util.Optional;

/** Runtime-only secret retrieval port; management APIs and metadata repositories never expose its value. */
public interface CredentialSecretProvider {
    Optional<String> resolve(String credentialRef);
}
