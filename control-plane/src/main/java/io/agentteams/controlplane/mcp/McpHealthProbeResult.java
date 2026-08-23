package io.agentteams.controlplane.mcp;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

public record McpHealthProbeResult(McpHealthStatus status, McpHealthProbeCategory category,
        Instant checkedAt, String detail) {

    public McpHealthProbeResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(checkedAt, "checkedAt");
        if (detail != null && detail.length() > 500) {
            throw new IllegalArgumentException("detail must be at most 500 characters");
        }
    }

    public static McpHealthProbeResult success(Instant checkedAt) {
        return new McpHealthProbeResult(McpHealthStatus.HEALTHY, McpHealthProbeCategory.SUCCESS, checkedAt, null);
    }

    public static McpHealthProbeResult failure(McpHealthProbeCategory category, Instant checkedAt, String detail) {
        if (category == McpHealthProbeCategory.SUCCESS || category == McpHealthProbeCategory.NOT_CHECKED) {
            throw new IllegalArgumentException("failure category must describe a failed probe");
        }
        return new McpHealthProbeResult(McpHealthStatus.UNHEALTHY, category, checkedAt, detail);
    }

    public static McpHealthProbeCategory classify(Throwable error) {
        String name = error == null ? "" : error.getClass().getName().toLowerCase(Locale.ROOT);
        if (name.contains("timeout")) return McpHealthProbeCategory.TIMEOUT;
        if (name.contains("unknownhost") || name.contains("dns")) return McpHealthProbeCategory.DNS_FAILURE;
        if (name.contains("ssl") || name.contains("tls")) return McpHealthProbeCategory.TLS_FAILURE;
        if (name.contains("protocol")) return McpHealthProbeCategory.PROTOCOL_ERROR;
        return McpHealthProbeCategory.CONNECTION_FAILURE;
    }
}
