package io.agentteams.controlplane.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcUsageBudgetRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-28T08:00:00Z");

    @Test
    void insertsPolicyWithProjectScopeAndNumericThresholds() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        UsageBudgetPolicy policy = policy();

        assertThat(new JdbcUsageBudgetRepository(jdbc).insert(policy)).isEqualTo(policy);

        verify(jdbc).update(contains("INSERT INTO usage_budget_policies"), any(Object[].class));
        verify(jdbc).update(contains("tenant_id"), any(Object[].class));
        verify(jdbc).update(contains("soft_threshold"), any(Object[].class));
    }

    @Test
    void updatesPolicyOnlyWhenExpectedVersionMatches() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(contains("WHERE id = ?"), any(Object[].class))).thenReturn(1);
        UsageBudgetPolicy policy = policy();

        UsageBudgetPolicy updated = new JdbcUsageBudgetRepository(jdbc).update(policy, 0);

        assertThat(updated.version()).isEqualTo(1);
        verify(jdbc).update(contains("WHERE id = ? AND tenant_id = ? AND project_id = ? AND version = ?"),
                any(Object[].class));
    }

    @Test
    void doesNotPersistDuplicateEvaluationFingerprint() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(contains("ON CONFLICT (fingerprint) DO NOTHING"), any(Object[].class))).thenReturn(0);

        assertThat(new JdbcUsageBudgetRepository(jdbc).insertEvaluationIfAbsent(policy(), evaluation(), "fingerprint-a"))
                .isFalse();
        verify(jdbc).update(contains("ON CONFLICT (fingerprint) DO NOTHING"), any(Object[].class));
    }

    @Test
    void migrationSeparatesCostStatusFromBudgetTables() throws Exception {
        String costMigration = Files.readString(Path.of("src/main/resources/db/migration/V52__model_call_audit_cost_status.sql"));
        String budgetMigration = Files.readString(Path.of("src/main/resources/db/migration/V53__usage_budget_forecast.sql"));
        String deliveryMigration = Files.readString(Path.of("src/main/resources/db/migration/V54__usage_budget_event_delivery.sql"));
        String backfillMigration = Files.readString(Path.of("src/main/resources/db/migration/V55__usage_dimension_backfill.sql"));

        assertThat(costMigration).contains("cost_status", "ESTIMATED", "UNPRICED");
        assertThat(budgetMigration).contains("usage_budget_policies", "usage_budget_evaluations",
                "usage_budget_events", "NUMERIC", "fingerprint");
        assertThat(budgetMigration).doesNotContain("prompt", "response", "authorization");
        assertThat(deliveryMigration).contains("attempts", "next_attempt_at", "last_error", "delivered_at", "updated_at");
        assertThat(backfillMigration).contains("model_call_audits", "tasks", "task_assignments", "team_tasks",
                "scope", "teamId", "candidate_count");
        assertThat(backfillMigration).contains("NULLIF(BTRIM", "HAVING COUNT(*) = 1")
                .doesNotContain("SET tenant_id = 'default'", "SET project_id = 'default'");
    }

    private static UsageBudgetPolicy policy() {
        return new UsageBudgetPolicy(UUID.randomUUID(), "tenant-a", "project-a", "USD", Duration.ofHours(24),
                new BigDecimal("10"), new BigDecimal("20"), Duration.ofHours(1), UsageBudgetPolicy.Status.ACTIVE,
                NOW, NOW, 0);
    }

    private static UsageBudgetEvaluation evaluation() {
        return new UsageBudgetEvaluation(UUID.randomUUID(), UUID.randomUUID(), NOW.minusSeconds(3600), NOW,
                new BigDecimal("2"), new BigDecimal("48"), UsageBudgetEvaluation.Status.UNDER_BUDGET, NOW);
    }
}
