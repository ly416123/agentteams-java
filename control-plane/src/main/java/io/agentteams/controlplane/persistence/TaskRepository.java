package io.agentteams.controlplane.persistence;

import io.agentteams.controlplane.api.CursorPageRequest;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
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

    public List<TaskListRecord> findPage(Principal principal, CursorPageRequest.Position after, int limit,
            CursorPageRequest.Direction direction, TaskPhase phase, UUID teamId, UUID workerId, String actor,
            Instant from, Instant to, String query) {
        String order = direction == CursorPageRequest.Direction.ASC
                ? " ORDER BY t.updated_at ASC, t.id ASC LIMIT ?"
                : " ORDER BY t.updated_at DESC, t.id DESC LIMIT ?";
        String cursor = after == null ? "" : direction == CursorPageRequest.Direction.ASC
                ? " AND (t.updated_at, t.id) > (?, ?)" : " AND (t.updated_at, t.id) < (?, ?)";
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.title, t.phase, t.priority, t.actor, t.source,
                       t.created_at, t.updated_at, t.version,
                       s.tenant_id, s.project_id, s.team,
                       team_ref.team_id, worker_ref.agent_id
                  FROM tasks t JOIN resource_scopes s ON s.resource_type = 'TASK' AND s.resource_id = t.id
                  LEFT JOIN LATERAL (SELECT tt.team_id FROM team_tasks tt
                                      WHERE tt.task_id = t.id ORDER BY tt.created_at, tt.team_id LIMIT 1) team_ref
                    ON TRUE
                  LEFT JOIN LATERAL (SELECT ta.agent_id FROM task_assignments ta
                                      WHERE ta.task_id = t.id ORDER BY ta.created_at, ta.id LIMIT 1) worker_ref
                    ON TRUE
                 WHERE s.tenant_id = ? AND s.project_id = ? AND s.team = ?
                   AND EXISTS (SELECT 1 FROM project_memberships m
                                JOIN projects p ON p.id = m.project_id AND p.tenant_id = m.tenant_id
                                WHERE m.tenant_id = s.tenant_id
                                  AND (m.project_id::text = s.project_id OR p.name = s.project_id)
                                  AND m.subject = ? AND m.status = 'ACTIVE')
                """);
        List<Object> args = new java.util.ArrayList<>(List.of(principal.scope().tenant(), principal.scope().project(),
                principal.scope().team(), principal.subject()));
        if (phase != null) { sql.append(" AND t.phase = ?"); args.add(phase.name()); }
        if (teamId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM team_tasks tt WHERE tt.task_id = t.id AND tt.team_id = ?)");
            args.add(teamId);
        }
        if (workerId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM task_assignments ta WHERE ta.task_id = t.id AND ta.agent_id = ?)");
            args.add(workerId);
        }
        if (actor != null && !actor.isBlank()) { sql.append(" AND t.actor = ?"); args.add(actor); }
        if (from != null) { sql.append(" AND t.updated_at >= ?"); args.add(JdbcSupport.timestamp(from)); }
        if (to != null) { sql.append(" AND t.updated_at < ?"); args.add(JdbcSupport.timestamp(to)); }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (t.title ILIKE ? OR t.description ILIKE ?)");
            String pattern = "%" + query.trim() + "%";
            args.add(pattern); args.add(pattern);
        }
        sql.append(cursor).append(order);
        if (after != null) { args.add(JdbcSupport.timestamp(after.updatedAt())); args.add(after.id()); }
        args.add(limit);
        return jdbc.query(sql.toString(), this::mapListItem, args.toArray());
    }

    public List<UUID> findIdsByPhase(TaskPhase phase, int limit) {
        if (phase == null) throw new IllegalArgumentException("phase must not be null");
        if (limit <= 0 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        return jdbc.query("""
                SELECT id FROM tasks
                 WHERE phase = ?
                 ORDER BY priority DESC, created_at ASC, id ASC
                 LIMIT ?
                """, (rs, row) -> rs.getObject("id", UUID.class), phase.name(), limit);
    }

    public TaskRecord updateState(TaskRecord next, long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE tasks
                   SET phase = ?, spec = ?, failure_code = ?, redacted_failure_message = ?,
                       updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, next.phase().name(), JdbcSupport.json(next.specJson()), next.failureCode(),
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

    private TaskListRecord mapListItem(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new TaskListRecord(rs.getObject("id", UUID.class), rs.getString("title"),
                TaskPhase.valueOf(rs.getString("phase")), rs.getInt("priority"), rs.getString("tenant_id"),
                rs.getString("project_id"), rs.getString("team"), rs.getString("actor"), rs.getString("source"),
                rs.getObject("team_id", UUID.class), rs.getObject("agent_id", UUID.class),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
