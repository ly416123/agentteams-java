package io.agentteams.controlplane.matrix;

import io.agentteams.controlplane.security.Principal;
import java.util.Objects;

/** Matrix sender bound to a platform identity and its authorization context. */
public record MatrixIdentity(String matrixUserId, Principal principal) {
    public MatrixIdentity {
        if (matrixUserId == null || matrixUserId.isBlank()) {
            throw new IllegalArgumentException("matrixUserId is required");
        }
        Objects.requireNonNull(principal, "principal");
    }

    public String subject() { return principal.subject(); }
    public io.agentteams.controlplane.security.AuthorizationService.Scope scope() { return principal.scope(); }
    public java.util.Set<String> permissions() { return principal.permissions(); }
}
