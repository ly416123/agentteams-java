package io.agentteams.controlplane.security;

import java.time.Instant;
import java.util.Objects;

public record IntegrationCredential(String accessKeyId, String accessKeySecret,
        SignatureAlgorithm algorithm, boolean active, Instant expiresAt, String integrationId,
        String organizationId) {
    public IntegrationCredential {
        if (accessKeyId == null || accessKeyId.isBlank()) throw new IllegalArgumentException("accessKeyId is required");
        if (accessKeySecret == null || accessKeySecret.isBlank()) throw new IllegalArgumentException("accessKeySecret is required");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (integrationId == null || integrationId.isBlank()) throw new IllegalArgumentException("integrationId is required");
        if (organizationId == null || organizationId.isBlank()) throw new IllegalArgumentException("organizationId is required");
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
