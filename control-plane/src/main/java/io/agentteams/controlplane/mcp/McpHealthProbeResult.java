package io.agentteams.controlplane.mcp;

import java.time.Instant;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

public record McpHealthProbeResult(McpHealthStatus status, McpHealthProbeCategory category,
        Instant checkedAt, String detail, long latencyMillis) {

    /** Compatibility constructor for callers that did not yet expose probe latency. */
    public McpHealthProbeResult(McpHealthStatus status, McpHealthProbeCategory category,
            Instant checkedAt, String detail) {
        this(status, category, checkedAt, detail, 0L);
    }

    public McpHealthProbeResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(checkedAt, "checkedAt");
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("latencyMillis must not be negative");
        }
        if (detail != null && detail.length() > 500) {
            throw new IllegalArgumentException("detail must be at most 500 characters");
        }
    }

    public static McpHealthProbeResult success(Instant checkedAt) {
        return success(checkedAt, 0L);
    }

    public static McpHealthProbeResult success(Instant checkedAt, long latencyMillis) {
        return new McpHealthProbeResult(McpHealthStatus.HEALTHY, McpHealthProbeCategory.SUCCESS, checkedAt,
                null, latencyMillis);
    }

    public static McpHealthProbeResult failure(McpHealthProbeCategory category, Instant checkedAt, String detail) {
        return failure(category, checkedAt, detail, 0L);
    }

    public static McpHealthProbeResult failure(McpHealthProbeCategory category, Instant checkedAt, String detail,
            long latencyMillis) {
        if (category == McpHealthProbeCategory.SUCCESS || category == McpHealthProbeCategory.NOT_CHECKED) {
            throw new IllegalArgumentException("failure category must describe a failed probe");
        }
        return new McpHealthProbeResult(McpHealthStatus.UNHEALTHY, category, checkedAt, detail, latencyMillis);
    }

    /** Probe-facing spelling; persisted MCP server health continues to use HEALTHY/UNHEALTHY. */
    public Status probeStatus() {
        return status == McpHealthStatus.HEALTHY ? Status.UP : Status.DOWN;
    }

    public String statusValue() {
        return probeStatus().name();
    }

    public Duration latency() {
        return Duration.ofMillis(latencyMillis);
    }

    public static McpHealthProbeCategory classify(Throwable error) {
        Throwable cause = error;
        while (cause != null && cause.getCause() != null) {
            if (cause instanceof McpHttpConnectorException httpError) {
                return classify(httpError.category());
            }
            cause = cause.getCause();
        }
        String name = error == null ? "" : error.getClass().getName().toLowerCase(Locale.ROOT);
        if (name.contains("timeout")) return McpHealthProbeCategory.TIMEOUT;
        if (name.contains("unknownhost") || name.contains("dns")) return McpHealthProbeCategory.DNS_FAILURE;
        if (name.contains("ssl") || name.contains("tls")) return McpHealthProbeCategory.TLS_FAILURE;
        if (name.contains("protocol")) return McpHealthProbeCategory.PROTOCOL_ERROR;
        return McpHealthProbeCategory.CONNECTION_FAILURE;
    }

    public static McpHealthProbeCategory classify(String connectorClassification) {
        String value = connectorClassification == null ? "" : connectorClassification.toUpperCase(Locale.ROOT);
        return switch (value) {
            case "CONNECTOR_TIMEOUT", "TIMEOUT" -> McpHealthProbeCategory.TIMEOUT;
            case "DNS_FAILURE" -> McpHealthProbeCategory.DNS_FAILURE;
            case "TLS_FAILURE" -> McpHealthProbeCategory.TLS_FAILURE;
            case "PROTOCOL_ERROR", "TRANSPORT_NOT_CONFIGURED" -> McpHealthProbeCategory.PROTOCOL_ERROR;
            case "ENDPOINT_NOT_ALLOWED", "SERVER_DISABLED", "SERVER_UNHEALTHY",
                    "OUTBOUND_POLICY_DENIED" -> McpHealthProbeCategory.POLICY_REJECTED;
            case "HTTP_ERROR", "UNAUTHORIZED", "FORBIDDEN", "RATE_LIMITED", "UPSTREAM_5XX",
                    "REDIRECT_NOT_ALLOWED" -> McpHealthProbeCategory.HTTP_ERROR;
            default -> McpHealthProbeCategory.CONNECTION_FAILURE;
        };
    }

    private static McpHealthProbeCategory classify(McpHttpFailureCategory category) {
        return switch (category) {
            case ENDPOINT_NOT_ALLOWED -> McpHealthProbeCategory.POLICY_REJECTED;
            case TIMEOUT -> McpHealthProbeCategory.TIMEOUT;
            case DNS_FAILURE -> McpHealthProbeCategory.DNS_FAILURE;
            case TLS_FAILURE -> McpHealthProbeCategory.TLS_FAILURE;
            case PROTOCOL_ERROR -> McpHealthProbeCategory.PROTOCOL_ERROR;
            case HTTP_ERROR, UNAUTHORIZED, FORBIDDEN, RATE_LIMITED, UPSTREAM_5XX,
                    REDIRECT_NOT_ALLOWED -> McpHealthProbeCategory.HTTP_ERROR;
            case CONNECTION_FAILURE -> McpHealthProbeCategory.CONNECTION_FAILURE;
        };
    }

    public enum Status {
        UP,
        DOWN
    }
}
