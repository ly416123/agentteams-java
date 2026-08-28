package io.agentteams.controlplane.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable notification state for one policy/window/threshold-status combination. */
public record UsageBudgetEvent(UUID id, String fingerprint, UUID policyId, String tenantId, String projectId,
        String currency, Instant windowStart, Instant windowEnd, BigDecimal actualCost, BigDecimal forecastCost,
        UsageBudgetEvaluation.Status evaluationStatus, Status status, int attempts, Instant nextAttemptAt,
        String lastError, Instant deliveredAt, Instant createdAt, Instant updatedAt) {

    public UsageBudgetEvent {
        Objects.requireNonNull(id, "id");
        requireText(fingerprint, "fingerprint");
        Objects.requireNonNull(policyId, "policyId");
        requireText(tenantId, "tenantId");
        requireText(projectId, "projectId");
        requireText(currency, "currency");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        if (!windowStart.isBefore(windowEnd)) throw new IllegalArgumentException("windowStart must be before windowEnd");
        nonNegative(actualCost, "actualCost");
        nonNegative(forecastCost, "forecastCost");
        Objects.requireNonNull(evaluationStatus, "evaluationStatus");
        if (evaluationStatus != UsageBudgetEvaluation.Status.SOFT_LIMIT
                && evaluationStatus != UsageBudgetEvaluation.Status.HARD_LIMIT) {
            throw new IllegalArgumentException("budget event must represent a threshold evaluation");
        }
        Objects.requireNonNull(status, "status");
        if (attempts < 1) throw new IllegalArgumentException("attempts must be at least 1");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static UsageBudgetEvent pending(String fingerprint, UsageBudgetPolicy policy,
            UsageBudgetEvaluation evaluation, Instant now) {
        return new UsageBudgetEvent(UUID.randomUUID(), fingerprint, policy.id(), policy.tenantId(), policy.projectId(),
                policy.currency(), evaluation.windowStart(), evaluation.windowEnd(), evaluation.actualCost(),
                evaluation.forecastCost(), evaluation.status(), Status.PENDING, 1, null, null, null, now, now);
    }

    public UsageBudgetEvent retryClaimed(Instant now) {
        return new UsageBudgetEvent(id, fingerprint, policyId, tenantId, projectId, currency, windowStart, windowEnd,
                actualCost, forecastCost, evaluationStatus, Status.PENDING, attempts + 1, null, null, deliveredAt,
                createdAt, now);
    }

    public UsageBudgetNotificationPort.UsageBudgetNotification notification() {
        return new UsageBudgetNotificationPort.UsageBudgetNotification(policyId, tenantId, projectId, currency, windowStart, windowEnd,
                actualCost, forecastCost, evaluationStatus);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    private static void nonNegative(BigDecimal value, String name) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException(name + " must be non-negative");
    }

    public enum Status { PENDING, SENT, FAILED }
}
