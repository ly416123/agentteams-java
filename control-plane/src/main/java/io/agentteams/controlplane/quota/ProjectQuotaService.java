package io.agentteams.controlplane.quota;

import io.agentteams.observability.ControlPlaneMetrics;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Small, durable project quota gate. Policy values of zero are unlimited. A missing
 * policy is deliberately compatible with the existing unlimited behavior.
 */
@Service
public class ProjectQuotaService {
    private static final String SELECT_SQL = """
            SELECT tenant_id, project_id, max_concurrent_calls, max_daily_calls, max_daily_tokens,
                   current_concurrent_calls, daily_calls, daily_tokens, usage_day
              FROM project_quota_policies
             WHERE tenant_id = ? AND project_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ControlPlaneMetrics metrics;

    @Autowired
    public ProjectQuotaService(DataSource dataSource, ControlPlaneMetrics metrics) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")), Clock.systemUTC(), metrics);
    }

    ProjectQuotaService(JdbcTemplate jdbc, Clock clock, ControlPlaneMetrics metrics) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Transactional
    public ProjectQuotaSnapshot putPolicy(ProjectQuotaPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO project_quota_policies
                    (tenant_id, project_id, max_concurrent_calls, max_daily_calls, max_daily_tokens,
                     usage_day, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, project_id) DO UPDATE SET
                    max_concurrent_calls = EXCLUDED.max_concurrent_calls,
                    max_daily_calls = EXCLUDED.max_daily_calls,
                    max_daily_tokens = EXCLUDED.max_daily_tokens,
                    updated_at = EXCLUDED.updated_at
                """, policy.tenantId(), policy.projectId(), policy.maxConcurrentCalls(),
                policy.maxDailyCalls(), policy.maxDailyTokens(), LocalDate.now(clock),
                Timestamp.from(now), Timestamp.from(now));
        return get(policy.tenantId(), policy.projectId()).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<ProjectQuotaSnapshot> get(String tenantId, String projectId) {
        validateScope(tenantId, projectId);
        LocalDate today = LocalDate.now(clock);
        return jdbc.query(SELECT_SQL, (rs, row) -> snapshot(rs, today), tenantId, projectId)
                .stream().findFirst();
    }

    /**
     * Atomically reserves one call and its estimated tokens. Callers must release
     * the returned lease in a finally block. Missing policy means no-op lease.
     */
    @Transactional(noRollbackFor = {QuotaExceededException.class, IllegalArgumentException.class})
    public ProjectQuotaLease acquire(String tenantId, String projectId, long estimatedTokens) {
        validateScope(tenantId, projectId);
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("estimatedTokens must not be negative");
        }
        LocalDate today = LocalDate.now(clock);
        Optional<Row> existing = jdbc.query(SELECT_SQL + " FOR UPDATE",
                (rs, row) -> row(rs), tenantId, projectId).stream().findFirst();
        if (existing.isEmpty()) {
            return new ProjectQuotaLease(tenantId, projectId, false);
        }

        Row current = existing.get();
        long dailyCalls = current.usageDay().equals(today) ? current.dailyCalls() : 0;
        long dailyTokens = current.usageDay().equals(today) ? current.dailyTokens() : 0;
        if (current.maxConcurrentCalls() > 0 && current.currentConcurrentCalls() >= current.maxConcurrentCalls()) {
            metrics.quotaRejected();
            throw new QuotaExceededException("concurrent_calls");
        }
        if (current.maxDailyCalls() > 0 && dailyCalls >= current.maxDailyCalls()) {
            metrics.quotaRejected();
            throw new QuotaExceededException("daily_calls");
        }
        if (current.maxDailyTokens() > 0
                && estimatedTokens > current.maxDailyTokens() - dailyTokens) {
            metrics.quotaRejected();
            throw new QuotaExceededException("daily_tokens");
        }

        jdbc.update("""
                UPDATE project_quota_policies
                   SET current_concurrent_calls = current_concurrent_calls + 1,
                       daily_calls = ?, daily_tokens = ?, usage_day = ?, updated_at = ?
                 WHERE tenant_id = ? AND project_id = ?
                """, dailyCalls + 1, dailyTokens + estimatedTokens, today,
                Timestamp.from(clock.instant()), tenantId, projectId);
        metrics.quotaAccepted();
        return new ProjectQuotaLease(tenantId, projectId, true);
    }

    @Transactional
    public void release(ProjectQuotaLease lease) {
        Objects.requireNonNull(lease, "lease");
        if (!lease.counted()) {
            return;
        }
        jdbc.update("""
                UPDATE project_quota_policies
                   SET current_concurrent_calls = GREATEST(current_concurrent_calls - 1, 0),
                       updated_at = ?
                 WHERE tenant_id = ? AND project_id = ?
                """, Timestamp.from(clock.instant()), lease.tenantId(), lease.projectId());
    }

    private ProjectQuotaSnapshot snapshot(java.sql.ResultSet rs, LocalDate today) throws java.sql.SQLException {
        Row row = row(rs);
        boolean currentDay = row.usageDay().equals(today);
        return new ProjectQuotaSnapshot(row.tenantId(), row.projectId(), true,
                row.maxConcurrentCalls(), row.maxDailyCalls(), row.maxDailyTokens(),
                row.currentConcurrentCalls(), currentDay ? row.dailyCalls() : 0,
                currentDay ? row.dailyTokens() : 0, today);
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getString("tenant_id"), rs.getString("project_id"),
                rs.getLong("max_concurrent_calls"), rs.getLong("max_daily_calls"),
                rs.getLong("max_daily_tokens"), rs.getLong("current_concurrent_calls"),
                rs.getLong("daily_calls"), rs.getLong("daily_tokens"),
                rs.getObject("usage_day", LocalDate.class));
    }

    private static void validateScope(String tenantId, String projectId) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId must not be blank");
        if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId must not be blank");
    }

    private record Row(String tenantId, String projectId, long maxConcurrentCalls, long maxDailyCalls,
            long maxDailyTokens, long currentConcurrentCalls, long dailyCalls, long dailyTokens,
            LocalDate usageDay) { }
}
