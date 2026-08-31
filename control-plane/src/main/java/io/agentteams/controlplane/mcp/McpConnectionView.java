package io.agentteams.controlplane.mcp;

import io.agentteams.application.api.McpConnectivityMode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Credential-free projection of an MCP connection for callers and audit consumers. */
public record McpConnectionView(UUID id, String name, McpConnectivityMode mode, String organizationId,
        String tenantId, String endpointRef, Set<String> allowedTools, boolean enabled, String connectorId,
        boolean credentialConfigured, String credentialRefDigest, Instant createdAt) {

    /** Compatibility accessor that is deliberately always redacted. */
    public String credentialRef() {
        return null;
    }
}
