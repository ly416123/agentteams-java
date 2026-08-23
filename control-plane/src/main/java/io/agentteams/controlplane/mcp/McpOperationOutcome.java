package io.agentteams.controlplane.mcp;

/** Stable, low-cardinality result categories for MCP runtime and audit consumers. */
public enum McpOperationOutcome {
    SUCCESS,
    DENIED,
    UNSUPPORTED,
    TIMEOUT,
    CONNECTOR_ERROR
}
