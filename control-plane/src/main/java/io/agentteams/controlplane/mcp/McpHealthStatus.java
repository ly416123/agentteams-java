package io.agentteams.controlplane.mcp;

import java.util.Locale;

public enum McpHealthStatus {
    UNKNOWN,
    HEALTHY,
    UNHEALTHY;

    public static McpHealthStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("healthStatus must be UNKNOWN, HEALTHY, or UNHEALTHY", error);
        }
    }
}
