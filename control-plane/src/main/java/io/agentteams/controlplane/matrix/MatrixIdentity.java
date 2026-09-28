package io.agentteams.controlplane.matrix;

import io.agentteams.controlplane.security.Principal;
import java.util.Objects;

/** Matrix sender bound to a platform identity and its authorization context. */
public record MatrixIdentity(String matrixUserId, Principal principal, String actorType) {
    private static final String DEFAULT_ACTOR_TYPE = "USER";

    public MatrixIdentity(String matrixUserId, Principal principal) {
        this(matrixUserId, principal, DEFAULT_ACTOR_TYPE);
    }

    public MatrixIdentity(String matrixUserId, Principal principal, String actorType) {
        this.matrixUserId = matrixUserId;
        this.principal = principal;
        this.actorType = actorType;
        if (matrixUserId == null || matrixUserId.isBlank()) {
            throw new IllegalArgumentException("matrixUserId is required");
        }
        Objects.requireNonNull(principal, "principal");
        if (actorType == null || actorType.isBlank()) {
            throw new IllegalArgumentException("actorType is required");
        }
    }

    public String subject() { return principal.subject(); }
    public String tenantId() { return principal.scope().tenant(); }
    public io.agentteams.controlplane.security.AuthorizationService.Scope scope() { return principal.scope(); }
    public java.util.Set<String> permissions() { return principal.permissions(); }
}
