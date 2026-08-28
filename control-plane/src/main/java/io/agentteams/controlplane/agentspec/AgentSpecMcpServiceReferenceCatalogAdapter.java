package io.agentteams.controlplane.agentspec;

import io.agentteams.controlplane.mcp.McpServerRecord;
import io.agentteams.controlplane.mcp.McpServerService;
import java.util.Objects;
import java.util.Optional;

/** AgentSpec adapter backed by the real MCP server registry service. */
public final class AgentSpecMcpServiceReferenceCatalogAdapter implements AgentSpecReferenceCatalogPort {

    private final McpServerService service;
    private final AgentSpecReferenceVisibility visibility;

    public AgentSpecMcpServiceReferenceCatalogAdapter(McpServerService service) {
        this(service, AgentSpecReferenceVisibility.allowAll());
    }

    public AgentSpecMcpServiceReferenceCatalogAdapter(McpServerService service,
            AgentSpecReferenceVisibility visibility) {
        this.service = Objects.requireNonNull(service, "service");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
    }

    @Override
    public AgentSpecReferenceType type() {
        return AgentSpecReferenceType.MCP;
    }

    @Override
    public Optional<AgentSpecReferenceCatalog.ReferenceMetadata> find(String referenceValue,
            AgentSpecReferenceCatalog.Scope scope) {
        if (referenceValue == null || referenceValue.isBlank() || scope == null) {
            return Optional.empty();
        }
        String normalized = referenceValue.trim();
        return service.list().stream()
                .filter(server -> server.id().toString().equals(normalized) || server.name().equals(normalized))
                .findFirst()
                .filter(server -> visibility.visible("MCP_SERVER", server.id(), scope))
                .map(server -> metadata(server, scope));
    }

    private AgentSpecReferenceCatalog.ReferenceMetadata metadata(McpServerRecord server,
            AgentSpecReferenceCatalog.Scope scope) {
        String revision = Long.toString(server.version());
        // Digest the canonical server identity, not the alias used by the AgentSpec. This keeps
        // name and UUID references bound to the same immutable server revision.
        AgentSpecReference reference = new AgentSpecReference(AgentSpecReferenceType.MCP,
                server.id().toString());
        return new AgentSpecReferenceCatalog.ReferenceMetadata(scope.tenantId(), scope.projectId(), scope.teamId(),
                AgentSpecReferenceCatalog.Visibility.PROJECT,
                server.enabled() ? "PUBLISHED" : "DISABLED", revision,
                AgentSpecReferenceDigest.derived(reference, revision), null, null,
                new McpRuntimeMetadata(server.id().toString(), server.transport().name(), server.endpoint(),
                        server.credentialRef()));
    }
}
