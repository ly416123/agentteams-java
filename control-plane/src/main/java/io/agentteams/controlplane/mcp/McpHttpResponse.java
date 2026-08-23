package io.agentteams.controlplane.mcp;

import java.util.Locale;
import java.util.Objects;

/** Sanitized HTTP response passed between the transport and MCP protocol parser. */
public record McpHttpResponse(int statusCode, String contentType, String body) {
    public McpHttpResponse {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode must be a valid HTTP status");
        }
        contentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        body = Objects.requireNonNull(body, "body");
    }

    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }

    public boolean isEventStream() {
        return contentType.startsWith("text/event-stream");
    }
}
