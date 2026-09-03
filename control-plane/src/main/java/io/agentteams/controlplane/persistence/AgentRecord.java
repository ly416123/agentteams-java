package io.agentteams.controlplane.persistence;

import io.agentteams.domain.agent.AgentPhase;
import io.agentteams.domain.agent.WorkerType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AgentRecord(
        UUID id,
        String name,
        WorkerType workerType,
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
        Objects.requireNonNull(workerType, "workerType");
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

    public AgentRecord(UUID id, String name, AgentPhase phase, String runtime,
            String capabilitiesJson, String metadataJson, Instant createdAt, Instant updatedAt, long version) {
        this(id, name, WorkerType.EXECUTOR, phase, runtime, capabilitiesJson, metadataJson,
                createdAt, updatedAt, version);
    }

    public static AgentRecord create(UUID id, String name, AgentPhase phase, String runtime,
            String capabilitiesJson, Instant now) {
        return new AgentRecord(id, name, WorkerType.EXECUTOR, phase, runtime, capabilitiesJson, "{}", now, now, 0);
    }

    public static AgentRecord create(UUID id, String name, WorkerType workerType, AgentPhase phase, String runtime,
            String capabilitiesJson, Instant now) {
        return new AgentRecord(id, name, workerType, phase, runtime, capabilitiesJson, "{}", now, now, 0);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
