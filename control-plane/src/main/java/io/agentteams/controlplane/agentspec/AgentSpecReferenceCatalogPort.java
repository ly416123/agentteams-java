package io.agentteams.controlplane.agentspec;

import java.util.Optional;
import java.util.Objects;

/**
 * Read-only, typed adapter boundary for an existing MODEL, SKILL, or MCP catalog.
 *
 * <p>The port returns metadata only. In particular, credentials, endpoints, package contents,
 * and other secret-bearing fields must remain owned by the source catalog.</p>
 */
public interface AgentSpecReferenceCatalogPort {

    AgentSpecReferenceType type();

    Optional<AgentSpecReferenceCatalog.ReferenceMetadata> find(
            String referenceValue, AgentSpecReferenceCatalog.Scope scope);

    /** Convenience form for callers that already hold a normalized reference. */
    default Optional<AgentSpecReferenceCatalog.ReferenceMetadata> find(
            AgentSpecReference reference, AgentSpecReferenceCatalog.Scope scope) {
        Objects.requireNonNull(reference, "reference");
        if (reference.type() != type()) {
            return Optional.empty();
        }
        return find(reference.value(), scope);
    }
}
