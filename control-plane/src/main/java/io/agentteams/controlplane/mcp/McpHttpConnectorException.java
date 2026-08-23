package io.agentteams.controlplane.mcp;

import java.util.Objects;

/**
 * Sanitized exception raised by the HTTP MCP connector.
 *
 * <p>The exception deliberately contains only a stable category and, when available, an HTTP
 * status. Response bodies, endpoint credentials and request arguments are never copied into the
 * exception message.</p>
 */
public final class McpHttpConnectorException extends RuntimeException {
    private final McpHttpFailureCategory category;
    private final int statusCode;

    public McpHttpConnectorException(McpHttpFailureCategory category, String message) {
        this(category, 0, message, null);
    }

    public McpHttpConnectorException(McpHttpFailureCategory category, int statusCode, String message) {
        this(category, statusCode, message, null);
    }

    public McpHttpConnectorException(McpHttpFailureCategory category, String message, Throwable cause) {
        this(category, 0, message, cause);
    }

    public McpHttpConnectorException(McpHttpFailureCategory category, int statusCode, String message,
            Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.category = Objects.requireNonNull(category, "category");
        if (statusCode < 0 || statusCode > 999) {
            throw new IllegalArgumentException("statusCode must be between 0 and 999");
        }
        this.statusCode = statusCode;
    }

    public McpHttpFailureCategory category() {
        return category;
    }

    /** Returns zero for failures without an HTTP response. */
    public int statusCode() {
        return statusCode;
    }
}
