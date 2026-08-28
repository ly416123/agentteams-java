package io.agentteams.controlplane.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UsageBudgetServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-28T08:00:00Z");

    @Test
    void forecastsCostLinearlyAfterOneHourAndAppliesHardLimit() {
        UsageBudgetEvaluation evaluation = new UsageBudgetService().evaluate(policy("10", "20"),
                new UsageBudgetService.CostObservation(new BigDecimal("6.00"), 2, 0, Duration.ofHours(2)), NOW);

        assertThat(evaluation.actualCost()).isEqualByComparingTo("6.00");
        assertThat(evaluation.forecastCost()).isEqualByComparingTo("72.00");
        assertThat(evaluation.status()).isEqualTo(UsageBudgetEvaluation.Status.HARD_LIMIT);
    }

    @Test
    void refusesForecastWhenObservedDataIsShorterThanOneHour() {
        UsageBudgetEvaluation evaluation = new UsageBudgetService().evaluate(policy("10", "20"),
                new UsageBudgetService.CostObservation(new BigDecimal("6.00"), 1, 0, Duration.ofMinutes(59)), NOW);

        assertThat(evaluation.forecastCost()).isNull();
        assertThat(evaluation.status()).isEqualTo(UsageBudgetEvaluation.Status.INSUFFICIENT_DATA);
    }

    @Test
    void marksWindowUnpricedWhenNoCallHasAUsablePrice() {
        UsageBudgetEvaluation evaluation = new UsageBudgetService().evaluate(policy("10", "20"),
                new UsageBudgetService.CostObservation(BigDecimal.ZERO, 0, 3, Duration.ofHours(2)), NOW);

        assertThat(evaluation.actualCost()).isNull();
        assertThat(evaluation.forecastCost()).isNull();
        assertThat(evaluation.status()).isEqualTo(UsageBudgetEvaluation.Status.UNPRICED);
    }

    private static UsageBudgetPolicy policy(String soft, String hard) {
        return new UsageBudgetPolicy(UUID.randomUUID(), "tenant-a", "project-a", "USD", Duration.ofHours(24),
                new BigDecimal(soft), new BigDecimal(hard), Duration.ofHours(1), UsageBudgetPolicy.Status.ACTIVE,
                NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(1)), 0);
    }
}
