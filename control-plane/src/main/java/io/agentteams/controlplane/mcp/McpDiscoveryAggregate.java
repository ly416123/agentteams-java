package io.agentteams.controlplane.mcp;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Safe cross-instance MCP discovery projection for one server revision. */
public record McpDiscoveryAggregate(
        UUID serverId,
        long serverRevision,
        McpDiscoveryStatus status,
        String toolsDigest,
        int healthyInstances,
        int freshInstances,
        Instant latestObservedAt,
        List<String> failureCategories) {

    public McpDiscoveryAggregate {
        Objects.requireNonNull(serverId, "serverId");
        if (serverRevision < 0) throw new IllegalArgumentException("serverRevision must not be negative");
        Objects.requireNonNull(status, "status");
        toolsDigest = toolsDigest == null ? "" : toolsDigest;
        if (healthyInstances < 0 || freshInstances < 0 || healthyInstances > freshInstances) {
            throw new IllegalArgumentException("aggregate instance counts are invalid");
        }
        failureCategories = List.copyOf(Objects.requireNonNull(failureCategories, "failureCategories"));
        if (status == McpDiscoveryStatus.UNKNOWN && (freshInstances != 0 || healthyInstances != 0)) {
            throw new IllegalArgumentException("unknown aggregate cannot have fresh instances");
        }
        if (status == McpDiscoveryStatus.AVAILABLE && healthyInstances == 0) {
            throw new IllegalArgumentException("available aggregate requires a healthy instance");
        }
        if (status == McpDiscoveryStatus.UNAVAILABLE && (freshInstances == 0 || healthyInstances != 0)) {
            throw new IllegalArgumentException("unavailable aggregate requires only fresh failed instances");
        }
    }
}
