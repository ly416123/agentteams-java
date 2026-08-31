package io.agentteams.controlplane.mcp;

import io.agentteams.application.api.McpConnectivityMode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Internal record for a tenant-owned MCP connection; never serialize directly to an API response. */
record McpConnection(UUID id, String name, McpConnectivityMode mode, String organizationId, String tenantId,
        String endpointRef, String credentialRef, Set<String> allowedTools, boolean enabled,
        String connectorId, String idempotencyKey, String requestHash, Instant createdAt) {
}
