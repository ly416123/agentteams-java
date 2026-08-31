package io.agentteams.controlplane.token;

import java.util.Objects;

/** Immutable ownership key for token accounting. */
public record TokenLedgerScope(String organizationId, String tenantId, String projectId) {
    public TokenLedgerScope {
        organizationId = required(organizationId, "organizationId");
        tenantId = required(tenantId, "tenantId");
        projectId = optional(projectId);
    }

    public String key() {
        return organizationId + "\u0000" + tenantId + "\u0000" + (projectId == null ? "" : projectId);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
