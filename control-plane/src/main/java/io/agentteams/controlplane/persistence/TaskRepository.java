package io.agentteams.controlplane.persistence;

import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class TaskRepository {

    private final JdbcTemplate jdbc;

    TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TaskRecord task) {
        jdbc.update("""
                INSERT INTO tasks
                    (id, title, description, phase, priority, spec, actor, source,
                     failure_code, redacted_failure_message, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, task.id(), task.title(), task.description(), task.phase().name(), task.priority(),
                JdbcSupport.json(task.specJson()), task.actor(), task.source(), task.failureCode(),
                JdbcSupport.failureMessage(task.redactedFailureMessage()), JdbcSupport.timestamp(task.createdAt()),
                JdbcSupport.timestamp(task.updatedAt()), task.version());
    }

    public Optional<TaskRecord> findById(UUID id) {
        return jdbc.query("""
                SELECT id, title, description, phase, priority, spec::text, actor, source,
                       failure_code, redacted_failure_message, created_at, updated_at, version
                  FROM tasks WHERE id = ?
                """, this::map, id).stream().findFirst();
    }

    public Optional<TaskRecord> findByIdForUpdate(UUID id) {
        return jdbc.query("""
                SELECT id, title, description, phase, priority, spec::text, actor, source,
                       failure_code, redacted_failure_message, created_at, updated_at, version
                  FROM tasks WHERE id = ? FOR UPDATE
                """, this::map, id).stream().findFirst();
    }

    public TaskRecord updateState(TaskRecord next, long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE tasks
                   SET phase = ?, failure_code = ?, redacted_failure_message = ?,
                       updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, next.phase().name(), next.failureCode(),
                JdbcSupport.failureMessage(next.redactedFailureMessage()),
                JdbcSupport.timestamp(next.updatedAt()), next.id(), expectedVersion);
        if (updated == 0) {
            throw new OptimisticLockFailure("task", next.id(), expectedVersion, actualVersion(next.id()));
        }
        return findById(next.id()).orElseThrow();
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM tasks", Long.class);
        return count == null ? 0 : count;
    }

    public TaskRecord updatePhase(UUID id, TaskPhase phase, long expectedVersion, Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE tasks
                   SET phase = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, phase.name(), JdbcSupport.timestamp(updatedAt), id, expectedVersion);
        if (updated == 0) {
            throw new OptimisticLockFailure("task", id, expectedVersion, actualVersion(id));
        }
        return findById(id).orElseThrow();
    }

    private long actualVersion(UUID id) {
        return jdbc.query("SELECT version FROM tasks WHERE id = ?", (rs, row) -> rs.getLong(1), id)
                .stream().findFirst().orElse(-1L);
    }

    private TaskRecord map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new TaskRecord(rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getString("description"), TaskPhase.valueOf(rs.getString("phase")),
                rs.getInt("priority"), rs.getString("spec"), rs.getString("actor"),
                rs.getString("source"), rs.getString("failure_code"),
                rs.getString("redacted_failure_message"), JdbcSupport.instant(rs, "created_at"),
                JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
