package io.agentteams.controlplane.mcp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record McpServerRecord(
        UUID id,
        String name,
        McpTransport transport,
        String endpoint,
        String credentialRef,
        boolean enabled,
        McpHealthStatus healthStatus,
        Instant lastCheckedAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public McpServerRecord {
        Objects.requireNonNull(id, "id");
        requireText(name, "name");
        Objects.requireNonNull(transport, "transport");
        requireText(endpoint, "endpoint");
        Objects.requireNonNull(healthStatus, "healthStatus");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
