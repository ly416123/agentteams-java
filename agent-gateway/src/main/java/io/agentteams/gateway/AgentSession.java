package io.agentteams.gateway;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persistable session projection; only a hash of the bearer token is retained. */
public record AgentSession(UUID agentId, String tokenSha256, Instant expiresAt, boolean revoked) {
    public AgentSession {
        Objects.requireNonNull(agentId, "agentId");
        if (tokenSha256 == null || tokenSha256.isBlank()) throw new IllegalArgumentException("tokenSha256 is required");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public boolean activeAt(Instant now) {
        return !revoked && now.isBefore(expiresAt);
    }
}
