package io.agentteams.controlplane.schedule;

import java.util.Objects;

/** Organization/tenant boundary for a scheduled task definition. */
public record ScheduledTaskScope(String organizationId, String tenantId, String projectId) {
    public ScheduledTaskScope {
        organizationId = required(organizationId, "organizationId");
        tenantId = required(tenantId, "tenantId");
        projectId = optional(projectId);
    }

    public String key() {
        return organizationId + ":" + tenantId + ":" + (projectId == null ? "" : projectId);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
