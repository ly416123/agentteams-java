package io.agentteams.controlplane.mcp;

import java.util.List;
import java.util.UUID;

/** Durable boundary for instance-scoped MCP discovery observations. */
public interface McpDiscoveryObservationPort {
    void record(McpDiscoveryObservation observation);

    List<McpDiscoveryObservation> find(UUID serverId, long serverRevision);
}
