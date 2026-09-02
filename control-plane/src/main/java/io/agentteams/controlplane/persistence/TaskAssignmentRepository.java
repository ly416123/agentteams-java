package io.agentteams.controlplane.persistence;

import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class TaskAssignmentRepository {

    private final JdbcTemplate jdbc;

    TaskAssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TaskAssignmentRecord assignment) {
        jdbc.update("""
                INSERT INTO task_assignments
                    (id, task_id, attempt_id, agent_id, phase, assigned_at, accepted_at, released_at,
                     details, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, assignment.id(), assignment.taskId(), assignment.attemptId(), assignment.agentId(),
                assignment.phase().name(), JdbcSupport.timestamp(assignment.assignedAt()),
                nullableTimestamp(assignment.acceptedAt()), nullableTimestamp(assignment.releasedAt()),
                JdbcSupport.json(assignment.detailsJson()), JdbcSupport.timestamp(assignment.createdAt()),
                JdbcSupport.timestamp(assignment.updatedAt()), assignment.version());
    }

    public Optional<TaskAssignmentRecord> findById(UUID id) {
        return jdbc.query("""
                SELECT id, task_id, attempt_id, agent_id, phase, assigned_at, accepted_at, released_at,
                       details::text, created_at, updated_at, version
                  FROM task_assignments WHERE id = ?
                """, this::map, id).stream().findFirst();
    }

    public List<TaskAssignmentRecord> findByTaskId(UUID taskId) {
        return jdbc.query("""
                SELECT id, task_id, attempt_id, agent_id, phase, assigned_at, accepted_at, released_at,
                       details::text, created_at, updated_at, version
                  FROM task_assignments WHERE task_id = ? ORDER BY created_at DESC, id DESC
                """, this::map, taskId);
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM task_assignments", Long.class);
        return count == null ? 0 : count;
    }

    public TaskAssignmentRecord updatePhase(UUID id, TaskPhase phase, long expectedVersion, Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE task_assignments
                   SET phase = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, phase.name(), JdbcSupport.timestamp(updatedAt), id, expectedVersion);
        if (updated == 0) {
            throw new OptimisticLockFailure("task_assignment", id, expectedVersion, actualVersion(id));
        }
        return findById(id).orElseThrow();
    }

    private long actualVersion(UUID id) {
        return jdbc.query("SELECT version FROM task_assignments WHERE id = ?", (rs, row) -> rs.getLong(1), id)
                .stream().findFirst().orElse(-1L);
    }

    private static java.sql.Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : JdbcSupport.timestamp(value);
    }

    private TaskAssignmentRecord map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        java.sql.Timestamp accepted = rs.getTimestamp("accepted_at");
        java.sql.Timestamp released = rs.getTimestamp("released_at");
        return new TaskAssignmentRecord(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getObject("attempt_id", UUID.class), rs.getObject("agent_id", UUID.class),
                TaskPhase.valueOf(rs.getString("phase")), JdbcSupport.instant(rs, "assigned_at"),
                accepted == null ? null : accepted.toInstant(), released == null ? null : released.toInstant(),
                rs.getString("details"), JdbcSupport.instant(rs, "created_at"),
                JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
