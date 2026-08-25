package io.agentteams.controlplane.dashboard;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable delivery state for one alert rule and one evaluation window. */
public record DashboardAlertEvent(UUID id, String fingerprint, String tenantId, String projectId,
        String rule, String severity, double actual, String message, Instant from, Instant to,
        Status status, int attempts, Instant nextAttemptAt, String lastError, Instant deliveredAt,
        Instant createdAt, Instant updatedAt) {

    public DashboardAlertEvent {
        Objects.requireNonNull(id, "id");
        requireText(fingerprint, "fingerprint");
        requireText(tenantId, "tenantId");
        requireText(projectId, "projectId");
        requireText(rule, "rule");
        requireText(severity, "severity");
        requireText(message, "message");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.isBefore(to)) throw new IllegalArgumentException("from must be before to");
        Objects.requireNonNull(status, "status");
        if (!Double.isFinite(actual) || actual < 0) throw new IllegalArgumentException("actual must be finite and non-negative");
        if (attempts < 0) throw new IllegalArgumentException("attempts must not be negative");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static DashboardAlertEvent pending(String fingerprint, String tenantId, String projectId,
            DashboardAlertService.Alert alert, Instant from, Instant to, Instant now) {
        Objects.requireNonNull(alert, "alert");
        return new DashboardAlertEvent(UUID.randomUUID(), fingerprint, tenantId, projectId,
                alert.rule(), alert.severity(), alert.actual(), alert.message(), from, to,
                Status.PENDING, 1, null, null, null, now, now);
    }

    public DashboardAlertEvent retryAt(Instant nextAttempt, String error, Instant now) {
        return new DashboardAlertEvent(id, fingerprint, tenantId, projectId, rule, severity, actual, message,
                from, to, Status.FAILED, attempts, nextAttempt, error, deliveredAt, createdAt, now);
    }

    public DashboardAlertEvent sentAt(Instant now) {
        return new DashboardAlertEvent(id, fingerprint, tenantId, projectId, rule, severity, actual, message,
                from, to, Status.SENT, attempts, null, null, now, createdAt, now);
    }

    public DashboardAlertEvent retryClaimed(Instant now) {
        return new DashboardAlertEvent(id, fingerprint, tenantId, projectId, rule, severity, actual, message,
                from, to, Status.PENDING, attempts + 1, null, null, deliveredAt, createdAt, now);
    }

    public DashboardAlertService.Alert alert() {
        return new DashboardAlertService.Alert(rule, severity, actual, message);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    public enum Status { PENDING, SENT, FAILED }
}
