package io.agentteams.controlplane.mcp;

import java.util.Locale;

public enum McpTransport {
    SSE,
    STREAMABLE_HTTP;

    public static McpTransport parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("transport is required");
        }
        try {
            return value.trim().toUpperCase(Locale.ROOT).equals("STREAMABLE-HTTP")
                    ? STREAMABLE_HTTP
                    : valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("transport must be SSE or STREAMABLE_HTTP", error);
        }
    }
}
