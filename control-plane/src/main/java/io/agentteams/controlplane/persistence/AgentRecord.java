package io.agentteams.controlplane.persistence;

import io.agentteams.domain.agent.AgentPhase;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AgentRecord(
        UUID id,
        String name,
        AgentPhase phase,
        String runtime,
        String capabilitiesJson,
        String metadataJson,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public AgentRecord {
        Objects.requireNonNull(id, "id");
        requireText(name, "name");
        Objects.requireNonNull(phase, "phase");
        requireText(runtime, "runtime");
        Objects.requireNonNull(capabilitiesJson, "capabilitiesJson");
        Objects.requireNonNull(metadataJson, "metadataJson");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    public static AgentRecord create(UUID id, String name, AgentPhase phase, String runtime,
            String capabilitiesJson, Instant now) {
        return new AgentRecord(id, name, phase, runtime, capabilitiesJson, "{}", now, now, 0);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
