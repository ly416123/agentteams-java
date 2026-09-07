package io.agentteams.controlplane.team;

import io.agentteams.controlplane.agentspec.AgentSpecReferenceParser;
import io.agentteams.controlplane.agentspec.AgentSpecReferenceValidationRequest;
import io.agentteams.controlplane.agentspec.AgentSpecReferenceValidator;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import java.util.Objects;

/** Applies Team scope, live membership and the existing AgentSpec reference catalog at publish time. */
public final class CatalogTeamRevisionPublishValidator implements TeamRevisionPublishValidator {
    private final TeamRevisionRepository revisions;
    private final ResourceScopeRepository scopes;
    private final AgentSpecReferenceValidator references;
    private final AgentSpecReferenceParser parser;

    public CatalogTeamRevisionPublishValidator(TeamRevisionRepository revisions, ResourceScopeRepository scopes,
            AgentSpecReferenceValidator references) {
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.references = Objects.requireNonNull(references, "references");
        this.parser = new AgentSpecReferenceParser();
    }

    @Override
    public void validate(TeamRevision revision) {
        scopes.requireVisible("TEAM", revision.teamId());
        // Team members are worker resources in the scope catalog. Using AGENT here
        // makes every publish fail with FORBIDDEN even when the caller can see the Worker.
        revision.memberAgentIds().forEach(worker -> scopes.requireVisible("WORKER", worker));
        revisions.validatePublish(revision);
        var parsed = parser.parse(revision.overlayJson());
        var request = PrincipalContext.current()
                .map(principal -> new AgentSpecReferenceValidationRequest(principal.scope().tenant(),
                        principal.scope().project(), principal.scope().team(), parsed))
                .orElseGet(() -> new AgentSpecReferenceValidationRequest(
                        io.agentteams.controlplane.agentspec.AgentSpecReferenceCatalog.Scope.unscoped(), parsed));
        var result = references.validate(request);
        if (!result.isValid()) {
            throw new TeamRevisionConflictException("Team revision references are not publishable: " + result.code());
        }
    }
}
