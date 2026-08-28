package io.agentteams.controlplane.mcp;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** One bounded, credential-blind discovery observation from a Control Plane instance. */
public record McpDiscoveryObservation(
        UUID serverId,
        long serverRevision,
        String instanceId,
        String toolsDigest,
        boolean healthy,
        String failureCategory,
        Instant observedAt,
        Instant expiresAt) {

    private static final Pattern INSTANCE_PATTERN = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final Set<String> FAILURE_CATEGORIES = Set.of(
            "SUCCESS", "TIMEOUT", "DNS_FAILURE", "TLS_FAILURE", "HTTP_ERROR",
            "PROTOCOL_ERROR", "POLICY_REJECTED", "CONNECTION_FAILURE", "UNKNOWN_ERROR");

    public McpDiscoveryObservation {
        Objects.requireNonNull(serverId, "serverId");
        if (serverRevision < 0) {
            throw new IllegalArgumentException("serverRevision must not be negative");
        }
        instanceId = requireInstanceId(instanceId);
        toolsDigest = normalizeDigest(toolsDigest);
        failureCategory = normalizeCategory(failureCategory);
        if (healthy && !failureCategory.isEmpty() && !"SUCCESS".equals(failureCategory)) {
            throw new IllegalArgumentException("healthy observation must use SUCCESS category");
        }
        if (!healthy && failureCategory.isEmpty()) {
            throw new IllegalArgumentException("unhealthy observation requires failure category");
        }
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(observedAt)) {
            throw new IllegalArgumentException("expiresAt must be after observedAt");
        }
    }

    private static String requireInstanceId(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || !INSTANCE_PATTERN.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("instanceId must be a bounded safe identifier");
        }
        return value.trim();
    }

    private static String normalizeDigest(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 128 || normalized.contains("\n") || normalized.contains("\r")) {
            throw new IllegalArgumentException("toolsDigest must be bounded");
        }
        return normalized;
    }

    private static String normalizeCategory(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!FAILURE_CATEGORIES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported MCP discovery failure category");
        }
        return normalized;
    }
}
