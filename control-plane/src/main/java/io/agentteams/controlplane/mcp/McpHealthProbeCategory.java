package io.agentteams.controlplane.mcp;

public enum McpHealthProbeCategory {
    NOT_CHECKED,
    SUCCESS,
    TIMEOUT,
    DNS_FAILURE,
    TLS_FAILURE,
    HTTP_ERROR,
    PROTOCOL_ERROR,
    POLICY_REJECTED,
    CONNECTION_FAILURE,
    UNKNOWN_ERROR
}
