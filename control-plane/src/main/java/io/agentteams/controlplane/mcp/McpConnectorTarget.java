package io.agentteams.controlplane.mcp;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/**
 * Safe connector-facing projection of an MCP server.
 *
 * <p>It deliberately contains no credential reference. Authentication is an adapter concern and
 * must be resolved by a future secret-aware connector without leaking control-plane metadata into
 * the generic MCP runtime SPI.</p>
 */
public record McpConnectorTarget(UUID serverId, String name, McpTransport transport, URI endpoint) {
    public McpConnectorTarget {
        Objects.requireNonNull(serverId, "serverId");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(endpoint, "endpoint");
    }

    public static McpConnectorTarget from(McpServerRecord server) {
        Objects.requireNonNull(server, "server");
        return new McpConnectorTarget(server.id(), server.name(), server.transport(), URI.create(server.endpoint()));
    }
}
