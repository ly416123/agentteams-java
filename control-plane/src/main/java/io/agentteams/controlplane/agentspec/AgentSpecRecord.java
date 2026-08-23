package io.agentteams.controlplane.agentspec;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AgentSpecRecord(
        UUID id,
        String name,
        String runtime,
        String modelProvider,
        String modelName,
        String teamRef,
        String desiredState,
        String lifecycleStatus,
        String specJson,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public AgentSpecRecord {
        Objects.requireNonNull(id, "id");
        requireText(name, "name");
        requireText(runtime, "runtime");
        requireText(modelProvider, "modelProvider");
        requireText(modelName, "modelName");
        requireText(desiredState, "desiredState");
        requireText(lifecycleStatus, "lifecycleStatus");
        requireText(specJson, "specJson");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
