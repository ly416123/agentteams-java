package io.agentteams.controlplane.security;

/**
 * Resolves the state of a credential reference without exposing credential
 * material to the control plane connection probe.
 *
 * <p>Implementations may be backed by a secret manager in a deployment, while
 * the default implementation deliberately performs validation only. The SPI
 * does not return a secret value, so callers cannot silently fall back to
 * treating a reference as plaintext.</p>
 */
public interface SecretResolver {

    Resolution resolve(String credentialRef);

    record Resolution(Status status) {
        public Resolution {
            if (status == null) {
                throw new NullPointerException("status");
            }
        }
    }

    enum Status {
        MISSING,
        INVALID_REFERENCE,
        VALIDATION_ONLY,
        RESOLVED,
        UNAVAILABLE
    }
}
