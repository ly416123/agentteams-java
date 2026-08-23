package io.agentteams.controlplane.security;

/** Default resolver: validates a reference and never reads a secret value. */
public final class ValidationOnlySecretResolver implements SecretResolver {

    @Override
    public Resolution resolve(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return new Resolution(Status.MISSING);
        }
        try {
            CredentialReferenceValidator.normalize(credentialRef);
            return new Resolution(Status.VALIDATION_ONLY);
        } catch (IllegalArgumentException error) {
            return new Resolution(Status.INVALID_REFERENCE);
        }
    }
}
