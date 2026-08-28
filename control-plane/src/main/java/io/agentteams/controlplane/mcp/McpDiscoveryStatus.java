package io.agentteams.controlplane.mcp;

/** Cross-instance status derived from fresh MCP discovery observations. */
public enum McpDiscoveryStatus {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN
}
