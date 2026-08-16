package io.agentteams.controlplane.security;

import java.util.Objects;
import java.util.Set;

/** Authenticated platform identity used by adapters before command authorization. */
public record Principal(String subject, AuthorizationService.Scope scope, Set<String> permissions) {
    public Principal {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
        Objects.requireNonNull(scope, "scope");
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
    }
}
