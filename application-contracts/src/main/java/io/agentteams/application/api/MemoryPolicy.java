package io.agentteams.application.api;

import java.time.Duration;
import java.util.Objects;

/** Scope and governance metadata for durable memory; behavior telemetry is not prompt content. */
public record MemoryPolicy(Scope scope, String organizationId, String tenantId, String projectId, String teamId,
        String subjectId, String taskId, Sensitivity sensitivity, Consent consent, Duration retention) {

    public MemoryPolicy(Scope scope, String organizationId, String tenantId, String projectId, String teamId,
            String subjectId, Sensitivity sensitivity, Consent consent, Duration retention) {
        this(scope, organizationId, tenantId, projectId, teamId, subjectId, null, sensitivity, consent, retention);
    }

    public MemoryPolicy {
        Objects.requireNonNull(scope, "scope");
        required(organizationId, "organizationId");
        required(tenantId, "tenantId");
        Objects.requireNonNull(sensitivity, "sensitivity");
        Objects.requireNonNull(consent, "consent");
        Objects.requireNonNull(retention, "retention");
        if (retention.isZero() || retention.isNegative()) throw new IllegalArgumentException("retention must be positive");
        if (scope == Scope.USER_PRIVATE && blank(subjectId)) {
            throw new IllegalArgumentException("USER_PRIVATE memory requires subjectId");
        }
        if (scope == Scope.PROJECT_SHARED && blank(projectId)) {
            throw new IllegalArgumentException("PROJECT_SHARED memory requires projectId");
        }
        if (scope == Scope.TEAM_SHARED && blank(teamId)) {
            throw new IllegalArgumentException("TEAM_SHARED memory requires teamId");
        }
        if (scope == Scope.TASK && blank(projectId) && blank(teamId)) {
            throw new IllegalArgumentException("TASK memory requires projectId or teamId");
        }
        if (scope == Scope.TASK && blank(taskId)) {
            throw new IllegalArgumentException("TASK memory requires taskId");
        }
    }

    public void requireUsableInModelContext() {
        if (consent != Consent.CONFIRMED) throw new IllegalArgumentException("memory consent is not confirmed");
        if (sensitivity == Sensitivity.RESTRICTED) {
            throw new IllegalArgumentException("restricted memory requires an explicit governed projection");
        }
    }

    private static void required(String value, String field) {
        if (blank(value)) throw new IllegalArgumentException(field + " must not be blank");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public enum Scope { USER_PRIVATE, ORGANIZATION_SHARED, PROJECT_SHARED, TEAM_SHARED, TASK }
    public enum Sensitivity { NORMAL, SENSITIVE, RESTRICTED }
    public enum Consent { CANDIDATE, CONFIRMED, REVOKED }
}
