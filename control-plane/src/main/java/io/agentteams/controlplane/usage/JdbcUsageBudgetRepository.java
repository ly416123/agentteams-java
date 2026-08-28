package io.agentteams.controlplane.usage;

import io.agentteams.controlplane.persistence.OptimisticLockFailure;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL repository for budget policies, evaluations and durable notification events. */
@Repository
public class JdbcUsageBudgetRepository implements UsageBudgetRepository {
    private static final String EVENT_COLUMNS = """
            SELECT e.id, e.fingerprint, e.policy_id, e.tenant_id, e.project_id, e.currency,
                   e.window_start, e.window_end, e.actual_cost, e.forecast_cost, e.evaluation_status,
                   e.status, e.attempts, e.next_attempt_at, e.last_error, e.delivered_at,
                   e.created_at, e.updated_at
              FROM usage_budget_events e
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcUsageBudgetRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcUsageBudgetRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public UsageBudgetPolicy insert(UsageBudgetPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        jdbc.update("""
                INSERT INTO usage_budget_policies
                    (id, tenant_id, project_id, currency, period_seconds, soft_threshold, hard_threshold,
                     forecast_window_seconds, status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, policy.id(), policy.tenantId(), policy.projectId(), policy.currency(), policy.period().toSeconds(),
                policy.softThreshold(), policy.hardThreshold(), policy.forecastWindow().toSeconds(),
                policy.status().name(), policy.version(), timestamp(policy.createdAt()), timestamp(policy.updatedAt()));
        return policy;
    }

    @Override
    public UsageBudgetPolicy update(UsageBudgetPolicy policy, long expectedVersion) {
        Objects.requireNonNull(policy, "policy");
        int updated = jdbc.update("""
                UPDATE usage_budget_policies
                   SET currency = ?, period_seconds = ?, soft_threshold = ?, hard_threshold = ?,
                       forecast_window_seconds = ?, status = ?, version = version + 1, updated_at = ?
                 WHERE id = ? AND tenant_id = ? AND project_id = ? AND version = ?
                """, policy.currency(), policy.period().toSeconds(), policy.softThreshold(), policy.hardThreshold(),
                policy.forecastWindow().toSeconds(), policy.status().name(), timestamp(policy.updatedAt()), policy.id(),
                policy.tenantId(), policy.projectId(), expectedVersion);
        if (updated == 1) {
            return new UsageBudgetPolicy(policy.id(), policy.tenantId(), policy.projectId(), policy.currency(),
                    policy.period(), policy.softThreshold(), policy.hardThreshold(), policy.forecastWindow(),
                    policy.status(), policy.createdAt(), policy.updatedAt(), expectedVersion + 1);
        }
        throw new OptimisticLockFailure("usage_budget_policy", policy.id(), expectedVersion, actualVersion(policy));
    }

    @Override
    public Optional<UsageBudgetPolicy> findById(UUID id, String tenantId, String projectId) {
        return jdbc.query("""
                SELECT id, tenant_id, project_id, currency, period_seconds, soft_threshold, hard_threshold,
                       forecast_window_seconds, status, version, created_at, updated_at
                  FROM usage_budget_policies
                 WHERE id = ? AND tenant_id = ? AND project_id = ?
                """, (rs, row) -> policy(rs), id, tenantId, projectId).stream().findFirst();
    }

    @Override
    public List<UsageBudgetPolicy> findAll(String tenantId, String projectId) {
        return List.copyOf(jdbc.query("""
                SELECT id, tenant_id, project_id, currency, period_seconds, soft_threshold, hard_threshold,
                       forecast_window_seconds, status, version, created_at, updated_at
                  FROM usage_budget_policies
                 WHERE tenant_id = ? AND project_id = ?
                 ORDER BY updated_at DESC, id
                """, (rs, row) -> policy(rs), tenantId, projectId));
    }

    @Override
    public List<UsageBudgetPolicy> findActive(int limit) {
        validateLimit(limit);
        return List.copyOf(jdbc.query("""
                SELECT id, tenant_id, project_id, currency, period_seconds, soft_threshold, hard_threshold,
                       forecast_window_seconds, status, version, created_at, updated_at
                  FROM usage_budget_policies
                 WHERE status = 'ACTIVE'
                 ORDER BY updated_at DESC, id
                 LIMIT ?
                """, (rs, row) -> policy(rs), limit));
    }

    @Override
    public void upsertEvaluation(UsageBudgetPolicy policy, UsageBudgetEvaluation evaluation) {
        jdbc.update(evaluationSql(), evaluation.id(), policy.id(), policy.tenantId(), policy.projectId(),
                timestamp(evaluation.windowStart()), timestamp(evaluation.windowEnd()), evaluation.actualCost(),
                evaluation.forecastCost(), evaluation.status().name(), timestamp(evaluation.evaluatedAt()));
    }

    @Override
    public boolean insertEventIfAbsent(UsageBudgetEvent event) {
        return jdbc.update("""
                INSERT INTO usage_budget_events
                    (id, policy_id, tenant_id, project_id, window_start, fingerprint, currency, window_end,
                     actual_cost, forecast_cost, evaluation_status, status, attempts, next_attempt_at, last_error,
                     delivered_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (fingerprint) DO NOTHING
                """, event.id(), event.policyId(), event.tenantId(), event.projectId(), timestamp(event.windowStart()),
                event.fingerprint(), event.currency(), timestamp(event.windowEnd()), event.actualCost(),
                event.forecastCost(), event.evaluationStatus().name(), event.status().name(), event.attempts(),
                nullableTimestamp(event.nextAttemptAt()), event.lastError(), nullableTimestamp(event.deliveredAt()),
                timestamp(event.createdAt()), timestamp(event.updatedAt())) == 1;
    }

    @Override
    public synchronized Optional<UsageBudgetEvent> claim(UsageBudgetEvent event, Instant now) {
        if (insertEventIfAbsent(event)) return selectEvent(event.fingerprint());
        int retried = jdbc.update("""
                UPDATE usage_budget_events
                   SET status = 'PENDING', attempts = attempts + 1, next_attempt_at = NULL,
                       last_error = NULL, updated_at = ?
                 WHERE fingerprint = ? AND status = 'FAILED' AND next_attempt_at <= ?
                """, timestamp(now), event.fingerprint(), timestamp(now));
        return retried == 1 ? selectEvent(event.fingerprint()) : Optional.empty();
    }

    @Override
    public List<UsageBudgetEvent> findPending(int limit) {
        validateLimit(limit);
        return jdbc.query(EVENT_COLUMNS + " WHERE e.status = 'PENDING' AND e.evaluation_status IN ('SOFT_LIMIT', 'HARD_LIMIT') ORDER BY e.updated_at, e.id LIMIT ?",
                this::event, limit);
    }

    @Override
    public List<UsageBudgetEvent> findDue(Instant now, int limit) {
        validateLimit(limit);
        return jdbc.query(EVENT_COLUMNS + " WHERE e.status = 'FAILED' AND e.next_attempt_at <= ? AND e.evaluation_status IN ('SOFT_LIMIT', 'HARD_LIMIT') ORDER BY e.updated_at, e.id LIMIT ?",
                this::event, timestamp(now), limit);
    }

    @Override
    public void markSent(UUID id, Instant at) {
        jdbc.update("""
                UPDATE usage_budget_events
                   SET status = 'SENT', next_attempt_at = NULL, last_error = NULL,
                       delivered_at = ?, updated_at = ?
                 WHERE id = ?
                """, timestamp(at), timestamp(at), id);
    }

    @Override
    public void markFailed(UUID id, Instant nextAttemptAt, String error, Instant at) {
        jdbc.update("""
                UPDATE usage_budget_events
                   SET status = 'FAILED', next_attempt_at = ?, last_error = ?, updated_at = ?
                 WHERE id = ?
                """, timestamp(nextAttemptAt), error, timestamp(at), id);
    }

    /** Compatibility method for the original persistence contract. */
    @Override
    public boolean insertEvaluationIfAbsent(UsageBudgetPolicy policy, UsageBudgetEvaluation evaluation,
            String fingerprint) {
        int inserted = jdbc.update("""
                INSERT INTO usage_budget_events
                    (id, policy_id, tenant_id, project_id, window_start, fingerprint, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)
                ON CONFLICT (fingerprint) DO NOTHING
                """, UUID.randomUUID(), policy.id(), policy.tenantId(), policy.projectId(),
                timestamp(evaluation.windowStart()), fingerprint, timestamp(evaluation.evaluatedAt()));
        if (inserted != 1) return false;
        upsertEvaluation(policy, evaluation);
        return true;
    }

    @Override
    public List<UsageBudgetEvaluation> findEvaluations(UUID policyId, String tenantId, String projectId, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return List.copyOf(jdbc.query("""
                SELECT id, policy_id, window_start, window_end, actual_cost, forecast_cost, status, evaluated_at
                  FROM usage_budget_evaluations
                 WHERE policy_id = ? AND tenant_id = ? AND project_id = ?
                 ORDER BY window_start DESC, id DESC
                 LIMIT ?
                """, (rs, row) -> new UsageBudgetEvaluation(rs.getObject("id", UUID.class),
                rs.getObject("policy_id", UUID.class), rs.getTimestamp("window_start").toInstant(),
                rs.getTimestamp("window_end").toInstant(), rs.getBigDecimal("actual_cost"),
                rs.getBigDecimal("forecast_cost"), UsageBudgetEvaluation.Status.valueOf(rs.getString("status")),
                rs.getTimestamp("evaluated_at").toInstant()), policyId, tenantId, projectId, limit));
    }

    private Optional<UsageBudgetEvent> selectEvent(String fingerprint) {
        return jdbc.query(EVENT_COLUMNS + " WHERE e.fingerprint = ?", this::event, fingerprint).stream().findFirst();
    }

    private UsageBudgetEvent event(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new UsageBudgetEvent(rs.getObject("id", UUID.class), rs.getString("fingerprint").trim(),
                rs.getObject("policy_id", UUID.class), rs.getString("tenant_id"), rs.getString("project_id"),
                rs.getString("currency"), rs.getTimestamp("window_start").toInstant(),
                rs.getTimestamp("window_end").toInstant(), rs.getBigDecimal("actual_cost"),
                rs.getBigDecimal("forecast_cost"), UsageBudgetEvaluation.Status.valueOf(rs.getString("evaluation_status")),
                UsageBudgetEvent.Status.valueOf(rs.getString("status")), rs.getInt("attempts"),
                instant(rs.getTimestamp("next_attempt_at")), rs.getString("last_error"),
                instant(rs.getTimestamp("delivered_at")), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private long actualVersion(UsageBudgetPolicy policy) {
        try {
            Long actual = jdbc.queryForObject("""
                    SELECT version FROM usage_budget_policies
                     WHERE id = ? AND tenant_id = ? AND project_id = ?
                    """, Long.class, policy.id(), policy.tenantId(), policy.projectId());
            return actual == null ? -1 : actual;
        } catch (EmptyResultDataAccessException error) {
            return -1;
        }
    }

    private static UsageBudgetPolicy policy(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UsageBudgetPolicy(rs.getObject("id", UUID.class), rs.getString("tenant_id"),
                rs.getString("project_id"), rs.getString("currency"),
                java.time.Duration.ofSeconds(rs.getLong("period_seconds")), rs.getBigDecimal("soft_threshold"),
                rs.getBigDecimal("hard_threshold"), java.time.Duration.ofSeconds(rs.getLong("forecast_window_seconds")),
                UsageBudgetPolicy.Status.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"));
    }

    private static String evaluationSql() {
        return """
                INSERT INTO usage_budget_evaluations
                    (id, policy_id, tenant_id, project_id, window_start, window_end, actual_cost, forecast_cost,
                     status, evaluated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (policy_id, window_start) DO UPDATE SET window_end = EXCLUDED.window_end,
                    actual_cost = EXCLUDED.actual_cost, forecast_cost = EXCLUDED.forecast_cost,
                    status = EXCLUDED.status, evaluated_at = EXCLUDED.evaluated_at
                """;
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(Objects.requireNonNull(value, "timestamp"));
    }

    private static Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
    }
}
