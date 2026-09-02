package io.agentteams.controlplane.task;

import io.agentteams.controlplane.persistence.JdbcSupport;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTaskRecoveryStateRepository implements TaskRecoveryStateRepository {
    private static final int DEFAULT_MAX_RECOVERY_ATTEMPTS = 3;
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcTaskRecoveryStateRepository(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    public JdbcTaskRecoveryStateRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<TaskRecoveryState> findByTaskId(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        return jdbc.query(select() + " WHERE task_id = ?", this::map, taskId).stream().findFirst();
    }

    @Override
    public TaskRecoveryState recordLeaseExpiry(UUID taskId, Instant at, String reason) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(at, "at");
        requireReason(reason);
        TaskRecoveryState current = jdbc.query(select() + " WHERE task_id = ? FOR UPDATE", this::map, taskId)
                .stream().findFirst().orElse(null);
        if (current == null) {
            jdbc.update("""
                    INSERT INTO task_recovery_states
                        (task_id, recovery_count, max_recovery_attempts, status, last_reason,
                         next_attempt_at, last_recovered_at, created_at, updated_at, version)
                    VALUES (?, 1, ?, 'READY', ?, ?, ?, ?, ?, 0)
                    """, taskId, DEFAULT_MAX_RECOVERY_ATTEMPTS, reason, JdbcSupport.timestamp(at.plusSeconds(1)),
                    JdbcSupport.timestamp(at), JdbcSupport.timestamp(at), JdbcSupport.timestamp(at));
            return findByTaskId(taskId).orElseThrow();
        }

        int nextCount = current.recoveryCount() + 1;
        boolean exhausted = nextCount > current.maxRecoveryAttempts();
        Instant nextAttemptAt = exhausted ? null : at.plus(backoff(nextCount));
        jdbc.update("""
                UPDATE task_recovery_states
                   SET recovery_count = ?, status = ?, last_reason = ?, next_attempt_at = ?,
                       last_recovered_at = ?, updated_at = ?, version = version + 1
                 WHERE task_id = ? AND version = ?
                """, nextCount, exhausted ? "RECOVERY_REQUIRED" : "READY", reason,
                nullableTimestamp(nextAttemptAt), JdbcSupport.timestamp(at), JdbcSupport.timestamp(at), taskId,
                current.version());
        return findByTaskId(taskId).orElseThrow();
    }

    @Override
    public void resetForManualRetry(UUID taskId, Instant at) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(at, "at");
        jdbc.update("""
                UPDATE task_recovery_states
                   SET recovery_count = 0, status = 'READY', last_reason = 'MANUAL_RETRY',
                       next_attempt_at = NULL, updated_at = ?, version = version + 1
                 WHERE task_id = ?
                """, JdbcSupport.timestamp(at), taskId);
    }

    static java.time.Duration backoff(int recoveryCount) {
        if (recoveryCount <= 0) throw new IllegalArgumentException("recoveryCount must be positive");
        return java.time.Duration.ofSeconds(1L << Math.min(recoveryCount - 1, 6));
    }

    private static String select() {
        return """
                SELECT task_id, recovery_count, max_recovery_attempts, status, last_reason,
                       next_attempt_at, last_recovered_at, created_at, updated_at, version
                  FROM task_recovery_states
                """;
    }

    private TaskRecoveryState map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new TaskRecoveryState(rs.getObject("task_id", UUID.class), rs.getInt("recovery_count"),
                rs.getInt("max_recovery_attempts"), rs.getString("status"), rs.getString("last_reason"),
                instant(rs, "next_attempt_at"), instant(rs, "last_recovered_at"),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 256) {
            throw new IllegalArgumentException("reason must be 1-256 characters");
        }
    }

    private static Object nullableTimestamp(Instant value) {
        return value == null ? null : JdbcSupport.timestamp(value);
    }
}
