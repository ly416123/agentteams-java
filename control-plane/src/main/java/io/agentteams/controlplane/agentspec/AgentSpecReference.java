package io.agentteams.controlplane.agentspec;

import java.util.Objects;

/** A normalized reference found in an AgentSpec. */
public record AgentSpecReference(AgentSpecReferenceType type, String value) {

    public AgentSpecReference {
        Objects.requireNonNull(type, "type");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("reference value must not be blank");
        }
        value = value.trim();
    }
}
