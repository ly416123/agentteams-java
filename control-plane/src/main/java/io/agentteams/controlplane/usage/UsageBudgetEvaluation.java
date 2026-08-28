package io.agentteams.controlplane.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable budget evaluation; a null amount means that it is not safely priced. */
public record UsageBudgetEvaluation(UUID id, UUID policyId, Instant windowStart, Instant windowEnd,
        BigDecimal actualCost, BigDecimal forecastCost, Status status, Instant evaluatedAt) {

    public enum Status { UNDER_BUDGET, SOFT_LIMIT, HARD_LIMIT, INSUFFICIENT_DATA, UNPRICED }

    public UsageBudgetEvaluation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        if (!windowStart.isBefore(windowEnd)) throw new IllegalArgumentException("windowStart must be before windowEnd");
        nonNegative(actualCost, "actualCost");
        nonNegative(forecastCost, "forecastCost");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if ((status == Status.UNPRICED || status == Status.INSUFFICIENT_DATA)
                && forecastCost != null) {
            throw new IllegalArgumentException(status + " evaluation must not have forecastCost");
        }
    }

    private static void nonNegative(BigDecimal value, String field) {
        if (value != null && value.signum() < 0) throw new IllegalArgumentException(field + " must not be negative");
    }
}
