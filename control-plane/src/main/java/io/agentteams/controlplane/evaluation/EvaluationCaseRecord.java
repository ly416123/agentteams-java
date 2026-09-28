package io.agentteams.controlplane.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EvaluationCaseRecord(UUID id, UUID suiteId, String caseKey, String name,
        JsonNode taskSpec, JsonNode assertions, Instant createdAt) {
    public EvaluationCaseRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(suiteId, "suiteId");
        caseKey = required(caseKey, "caseKey");
        name = required(name, "name");
        if (taskSpec == null || !taskSpec.isObject()) {
            throw new IllegalArgumentException("taskSpec must be a JSON object");
        }
        if (assertions == null || !assertions.isArray()) {
            throw new IllegalArgumentException("assertions must be a JSON array");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
