package io.agentteams.controlplane.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Delivery boundary for budget notifications; no request content or credentials cross this port. */
public interface UsageBudgetNotificationPort {
    NotificationResult notify(UsageBudgetNotification notification);

    record UsageBudgetNotification(UUID policyId, String tenantId, String projectId, String currency,
            Instant windowStart, Instant windowEnd, BigDecimal actualCost, BigDecimal forecastCost,
            UsageBudgetEvaluation.Status status) {
        public UsageBudgetNotification {
            Objects.requireNonNull(policyId, "policyId");
            requireText(tenantId, "tenantId");
            requireText(projectId, "projectId");
            requireText(currency, "currency");
            Objects.requireNonNull(windowStart, "windowStart");
            Objects.requireNonNull(windowEnd, "windowEnd");
            if (!windowStart.isBefore(windowEnd)) throw new IllegalArgumentException("windowStart must be before windowEnd");
            Objects.requireNonNull(actualCost, "actualCost");
            Objects.requireNonNull(forecastCost, "forecastCost");
            if (actualCost.signum() < 0 || forecastCost.signum() < 0) {
                throw new IllegalArgumentException("notification costs must not be negative");
            }
            if (status != UsageBudgetEvaluation.Status.SOFT_LIMIT
                    && status != UsageBudgetEvaluation.Status.HARD_LIMIT) {
                throw new IllegalArgumentException("only threshold evaluations can be notified");
            }
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        }
    }

    record NotificationResult(String channel, boolean delivered) {
        public NotificationResult {
            Objects.requireNonNull(channel, "channel");
        }
    }
}
