package io.agentteams.controlplane.security;

import java.util.Objects;

/** Stable organization-to-task context shared by authorization and execution adapters. */
public record ExecutionContext(String organizationId, String tenantId, String projectId, String teamId,
        String subjectId) {
    public ExecutionContext {
        requireText(organizationId, "organizationId");
        requireText(tenantId, "tenantId");
        requireText(projectId, "projectId");
        requireText(teamId, "teamId");
        requireText(subjectId, "subjectId");
    }

    public boolean belongsTo(ExecutionContext other) {
        Objects.requireNonNull(other, "other");
        return sameResourceScope(other) && subjectId.equals(other.subjectId);
    }

    /** Returns whether both contexts address the same organization resource scope. */
    public boolean sameResourceScope(ExecutionContext other) {
        Objects.requireNonNull(other, "other");
        return organizationId.equals(other.organizationId)
                && tenantId.equals(other.tenantId)
                && projectId.equals(other.projectId)
                && teamId.equals(other.teamId);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
