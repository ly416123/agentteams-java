package io.agentteams.controlplane.security;

/**
 * Explicit External Secrets boundary. It validates references but performs no
 * network call until a deployment-owned adapter is supplied.
 */
public final class ExternalSecretsSecretResolver implements SecretResolver {

    @Override
    public Resolution resolve(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return new Resolution(Status.MISSING);
        }
        try {
            CredentialReferenceValidator.normalize(credentialRef);
            return new Resolution(Status.UNAVAILABLE);
        } catch (IllegalArgumentException error) {
            return new Resolution(Status.INVALID_REFERENCE);
        }
    }
}
