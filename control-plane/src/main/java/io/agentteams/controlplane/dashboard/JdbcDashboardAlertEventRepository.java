package io.agentteams.controlplane.dashboard;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed alert event state with a unique fingerprint for idempotency. */
@Repository
public class JdbcDashboardAlertEventRepository implements DashboardAlertEventRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, fingerprint, tenant_id, project_id, rule, severity, actual, message,
                   from_at, to_at, status, attempts, next_attempt_at, last_error, delivered_at,
                   created_at, updated_at
              FROM dashboard_alert_events
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcDashboardAlertEventRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcDashboardAlertEventRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public synchronized Optional<DashboardAlertEvent> claim(DashboardAlertEvent candidate, Instant now) {
        int inserted = jdbc.update("""
                INSERT INTO dashboard_alert_events
                    (id, fingerprint, tenant_id, project_id, rule, severity, actual, message,
                     from_at, to_at, status, attempts, next_attempt_at, last_error, delivered_at,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (fingerprint) DO NOTHING
                """, candidate.id(), candidate.fingerprint(), candidate.tenantId(), candidate.projectId(),
                candidate.rule(), candidate.severity(), candidate.actual(), candidate.message(),
                timestamp(candidate.from()), timestamp(candidate.to()), candidate.status().name(), candidate.attempts(),
                nullableTimestamp(candidate.nextAttemptAt()), candidate.lastError(), nullableTimestamp(candidate.deliveredAt()),
                timestamp(candidate.createdAt()), timestamp(candidate.updatedAt()));
        if (inserted == 1) return selectByFingerprint(candidate.fingerprint());
        int retried = jdbc.update("""
                UPDATE dashboard_alert_events
                   SET status = 'PENDING', attempts = attempts + 1, next_attempt_at = NULL,
                       last_error = NULL, updated_at = ?
                 WHERE fingerprint = ? AND status = 'FAILED' AND next_attempt_at <= ?
                """, timestamp(now), candidate.fingerprint(), timestamp(now));
        return retried == 1 ? selectByFingerprint(candidate.fingerprint()) : Optional.empty();
    }

    @Override
    public List<DashboardAlertEvent> findDue(Instant now, int limit) {
        validateLimit(limit);
        return jdbc.query(SELECT_COLUMNS + " WHERE status = 'FAILED' AND next_attempt_at <= ? ORDER BY updated_at LIMIT ?",
                this::map, timestamp(now), limit);
    }

    @Override
    public List<DashboardAlertEvent> findRecent(String tenantId, String projectId, int limit) {
        validateLimit(limit);
        return jdbc.query(SELECT_COLUMNS + " WHERE tenant_id = ? AND project_id = ? ORDER BY updated_at DESC LIMIT ?",
                this::map, tenantId, projectId, limit);
    }

    @Override
    public List<AlertScope> findUsageScopes() {
        return jdbc.query("""
                SELECT DISTINCT tenant_id, project_id
                  FROM model_call_audits
                 WHERE tenant_id IS NOT NULL AND tenant_id <> ''
                   AND project_id IS NOT NULL AND project_id <> ''
                 ORDER BY tenant_id, project_id
                """, (rs, row) -> new AlertScope(rs.getString("tenant_id"), rs.getString("project_id")));
    }

    @Override
    public void markSent(UUID id, Instant at) {
        jdbc.update("""
                UPDATE dashboard_alert_events
                   SET status = 'SENT', next_attempt_at = NULL, last_error = NULL,
                       delivered_at = ?, updated_at = ?
                 WHERE id = ?
                """, timestamp(at), timestamp(at), id);
    }

    @Override
    public void markFailed(UUID id, Instant nextAttemptAt, String error, Instant at) {
        jdbc.update("""
                UPDATE dashboard_alert_events
                   SET status = 'FAILED', next_attempt_at = ?, last_error = ?, updated_at = ?
                 WHERE id = ?
                """, timestamp(nextAttemptAt), error, timestamp(at), id);
    }

    private Optional<DashboardAlertEvent> selectByFingerprint(String fingerprint) {
        return jdbc.query(SELECT_COLUMNS + " WHERE fingerprint = ?", this::map, fingerprint)
                .stream().findFirst();
    }

    private DashboardAlertEvent map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new DashboardAlertEvent(rs.getObject("id", UUID.class), rs.getString("fingerprint"),
                rs.getString("tenant_id"), rs.getString("project_id"), rs.getString("rule"),
                rs.getString("severity"), rs.getDouble("actual"), rs.getString("message"),
                rs.getTimestamp("from_at").toInstant(), rs.getTimestamp("to_at").toInstant(),
                DashboardAlertEvent.Status.valueOf(rs.getString("status")), rs.getInt("attempts"),
                instant(rs.getTimestamp("next_attempt_at")), rs.getString("last_error"),
                instant(rs.getTimestamp("delivered_at")), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(Objects.requireNonNull(value, "timestamp"));
    }

    private static Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
    }
}
