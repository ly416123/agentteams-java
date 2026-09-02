package io.agentteams.controlplane.persistence;

import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class TaskAttemptRepository {

    private final JdbcTemplate jdbc;

    TaskAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TaskAttemptRecord attempt) {
        jdbc.update("""
                INSERT INTO task_attempts
                    (id, task_id, lease_id, phase, lease_expires_at, completed_at, actor, source,
                     failure_code, redacted_failure_message, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, attempt.id(), attempt.taskId(), attempt.leaseId(), attempt.phase().name(),
                JdbcSupport.timestamp(attempt.leaseExpiresAt()), nullableTimestamp(attempt.completedAt()),
                attempt.actor(), attempt.source(), attempt.failureCode(),
                JdbcSupport.failureMessage(attempt.redactedFailureMessage()),
                JdbcSupport.timestamp(attempt.createdAt()), JdbcSupport.timestamp(attempt.updatedAt()),
                attempt.version());
    }

    public Optional<TaskAttemptRecord> findById(UUID id) {
        return jdbc.query("""
                SELECT id, task_id, lease_id, phase, lease_expires_at, completed_at, actor, source,
                       failure_code, redacted_failure_message, created_at, updated_at, version
                  FROM task_attempts WHERE id = ?
                """, this::map, id).stream().findFirst();
    }

    public List<TaskAttemptRecord> findByTaskId(UUID taskId) {
        return jdbc.query("""
                SELECT id, task_id, lease_id, phase, lease_expires_at, completed_at, actor, source,
                       failure_code, redacted_failure_message, created_at, updated_at, version
                  FROM task_attempts WHERE task_id = ? ORDER BY created_at DESC, id DESC
                """, this::map, taskId);
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM task_attempts", Long.class);
        return count == null ? 0 : count;
    }

    public TaskAttemptRecord updatePhase(UUID id, TaskPhase phase, Instant completedAt,
            String failureCode, String redactedFailureMessage, long expectedVersion, Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE task_attempts
                   SET phase = ?, completed_at = ?, failure_code = ?, redacted_failure_message = ?,
                       updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, phase.name(), nullableTimestamp(completedAt), failureCode,
                JdbcSupport.failureMessage(redactedFailureMessage),
                JdbcSupport.timestamp(updatedAt), id, expectedVersion);
        if (updated == 0) {
            throw new OptimisticLockFailure("task_attempt", id, expectedVersion, actualVersion(id));
        }
        return findById(id).orElseThrow();
    }

    public TaskAttemptRecord updateLease(UUID id, Instant leaseExpiresAt, String actor, String source,
            long expectedVersion, Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE task_attempts
                   SET lease_expires_at = ?, actor = ?, source = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, JdbcSupport.timestamp(leaseExpiresAt), actor, source, JdbcSupport.timestamp(updatedAt),
                id, expectedVersion);
        if (updated == 0) {
            throw new OptimisticLockFailure("task_attempt", id, expectedVersion, actualVersion(id));
        }
        return findById(id).orElseThrow();
    }

    private long actualVersion(UUID id) {
        return jdbc.query("SELECT version FROM task_attempts WHERE id = ?", (rs, row) -> rs.getLong(1), id)
                .stream().findFirst().orElse(-1L);
    }

    private static java.sql.Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : JdbcSupport.timestamp(value);
    }

    private TaskAttemptRecord map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        java.sql.Timestamp completed = rs.getTimestamp("completed_at");
        return new TaskAttemptRecord(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getObject("lease_id", UUID.class), TaskPhase.valueOf(rs.getString("phase")),
                JdbcSupport.instant(rs, "lease_expires_at"), completed == null ? null : completed.toInstant(),
                rs.getString("actor"), rs.getString("source"), rs.getString("failure_code"),
                rs.getString("redacted_failure_message"), JdbcSupport.instant(rs, "created_at"),
                JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
