package io.agentteams.controlplane.mcp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Connector route health projection; it never contains endpoint credentials or payloads. */
public record McpGatewayRoute(UUID id, UUID connectionId, String connectorId, long routeVersion,
        Status status, Instant lastHeartbeatAt, String healthSummaryJson, Instant createdAt, Instant updatedAt) {
    public McpGatewayRoute {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(connectionId, "connectionId");
        required(connectorId, "connectorId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        healthSummaryJson = healthSummaryJson == null || healthSummaryJson.isBlank() ? "{}" : healthSummaryJson.trim();
        if (routeVersion < 0) throw new IllegalArgumentException("routeVersion must not be negative");
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    public enum Status { ACTIVE, STALE, DISABLED }
}
