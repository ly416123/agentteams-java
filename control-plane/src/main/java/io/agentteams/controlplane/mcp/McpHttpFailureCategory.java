package io.agentteams.controlplane.mcp;

/** Low-cardinality, non-sensitive failure categories for the HTTP MCP connector. */
public enum McpHttpFailureCategory {
    ENDPOINT_NOT_ALLOWED,
    TIMEOUT,
    REDIRECT_NOT_ALLOWED,
    UNAUTHORIZED,
    FORBIDDEN,
    RATE_LIMITED,
    UPSTREAM_5XX,
    HTTP_ERROR,
    DNS_FAILURE,
    TLS_FAILURE,
    CONNECTION_FAILURE,
    PROTOCOL_ERROR
}
