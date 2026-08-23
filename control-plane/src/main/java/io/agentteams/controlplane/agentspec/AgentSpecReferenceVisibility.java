package io.agentteams.controlplane.agentspec;

import java.util.UUID;

/** Resolves resource ownership for an explicit AgentSpec caller scope. */
@FunctionalInterface
public interface AgentSpecReferenceVisibility {

    boolean visible(String resourceType, UUID resourceId, AgentSpecReferenceCatalog.Scope scope);

    static AgentSpecReferenceVisibility allowAll() {
        return (resourceType, resourceId, scope) -> true;
    }
}
