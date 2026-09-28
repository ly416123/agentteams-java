package io.agentteams.controlplane.evaluation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EvaluationSuiteRecord(UUID id, String tenantId, String projectId, String suiteKey,
        String version, String evaluatorVersion, Instant createdAt) {
    public EvaluationSuiteRecord {
        Objects.requireNonNull(id, "id");
        tenantId = required(tenantId, "tenantId");
        projectId = required(projectId, "projectId");
        suiteKey = identifier(suiteKey, "suiteKey");
        version = required(version, "version");
        evaluatorVersion = identifier(evaluatorVersion, "evaluatorVersion");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String identifier(String value, String field) {
        String normalized = required(value, field);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be an identifier");
        }
        return normalized;
    }
}
