package io.agentteams.application.api;

import java.util.Objects;

/** Stable provider identity for one sandbox resource. */
public record SandboxProviderRef(String provider, String resourceId, String resourceUid) {

    public SandboxProviderRef {
        provider = required(provider, "provider");
        resourceId = required(resourceId, "resourceId");
        resourceUid = required(resourceUid, "resourceUid");
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
