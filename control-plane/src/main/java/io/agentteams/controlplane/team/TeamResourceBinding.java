package io.agentteams.controlplane.team;

import java.util.Objects;
import java.util.UUID;

/** A non-secret, immutable Team resource pin. */
public record TeamResourceBinding(TeamResourceType type, UUID resourceId, String resourceRevision, String digest) {
    public TeamResourceBinding {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(resourceId, "resourceId");
        resourceRevision = required(resourceRevision, "resourceRevision");
        digest = required(digest, "digest");
        if (resourceRevision.matches("0+")) {
            throw new IllegalArgumentException("resourceRevision must be positive");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
