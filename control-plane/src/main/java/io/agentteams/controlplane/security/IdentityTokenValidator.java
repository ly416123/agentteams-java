package io.agentteams.controlplane.security;

import java.util.Optional;

/** Adapter seam for OIDC/JWT validation; cryptographic validation stays outside business services. */
@FunctionalInterface
public interface IdentityTokenValidator {
    Optional<IdentityPrincipal> validate(String bearerToken);

    record IdentityPrincipal(String subject, AuthorizationService.Scope scope, java.util.Set<String> permissions) {
        public IdentityPrincipal {
            if (subject == null || subject.isBlank()) throw new IllegalArgumentException("subject is required");
            java.util.Objects.requireNonNull(scope, "scope");
            permissions = java.util.Set.copyOf(java.util.Objects.requireNonNull(permissions, "permissions"));
        }
    }
}
