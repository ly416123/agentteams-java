package io.agentteams.controlplane.agentspec;

import java.util.Objects;

/** Caller scope and references supplied to an AgentSpec reference validator. */
public record AgentSpecReferenceValidationRequest(
        String tenantId,
        String projectId,
        String teamId,
        AgentSpecReferences references) {

    /** Compatibility constructor for callers that only carry tenant/project scope. */
    public AgentSpecReferenceValidationRequest(String tenantId, String projectId, AgentSpecReferences references) {
        this(tenantId, projectId, null, references);
    }

    public AgentSpecReferenceValidationRequest(AgentSpecReferenceCatalog.Scope scope,
            AgentSpecReferences references) {
        this(Objects.requireNonNull(scope, "scope").tenantId(), scope.projectId(), scope.teamId(), references);
    }

    public AgentSpecReferenceValidationRequest {
        Objects.requireNonNull(references, "references");
        if (tenantId != null) {
            tenantId = tenantId.trim();
        }
        if (projectId != null) {
            projectId = projectId.trim();
        }
        if (teamId != null) {
            teamId = teamId.trim();
        }
    }

    public AgentSpecReferenceCatalog.Scope scope() {
        return new AgentSpecReferenceCatalog.Scope(tenantId, projectId, teamId);
    }
}
