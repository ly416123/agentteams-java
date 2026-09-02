package io.agentteams.controlplane.task;

import io.agentteams.controlplane.persistence.JdbcSupport;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL snapshot and durable finding store for Task state reconciliation. */
@Repository
public class JdbcTaskStateConsistencyRepository implements TaskStateConsistencyRepository {
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcTaskStateConsistencyRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcTaskStateConsistencyRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<TaskStateConsistencySnapshot> findSnapshots(Instant since, int limit) {
        Objects.requireNonNull(since, "since");
        validateLimit(limit);
        return jdbc.query("""
                SELECT task.id AS task_id, run.id AS run_id, run.organization_id, run.tenant_id,
                       task.phase AS task_phase, run.status AS run_status,
                       (SELECT manifest.status FROM task_result_manifests manifest
                         WHERE manifest.run_id = run.id) AS manifest_status,
                       (SELECT COUNT(*) FROM task_attempts attempt
                         WHERE attempt.task_id = task.id AND attempt.completed_at IS NULL
                           AND attempt.phase IN ('ASSIGNED', 'ACCEPTED', 'RUNNING')) AS active_attempt_count,
                       (SELECT COUNT(*) FROM agent_leases lease
                         JOIN task_attempts attempt ON attempt.id = lease.task_attempt_id
                         WHERE attempt.task_id = task.id AND lease.status = 'ACTIVE'
                           AND lease.released_at IS NULL) AS active_lease_count,
                       (SELECT COUNT(*) FROM task_process_events event WHERE event.run_id = run.id)
                           AS process_event_count,
                       (SELECT COALESCE(MAX(event.sequence), -1) FROM task_process_events event
                         WHERE event.run_id = run.id) AS max_process_sequence,
                       (SELECT COUNT(*) FROM task_subtasks subtask WHERE subtask.run_id = run.id
                         AND subtask.status IN ('PENDING', 'RUNNING', 'BLOCKED')) AS unfinished_subtask_count,
                       GREATEST(task.updated_at, run.updated_at) AS observed_at
                  FROM task_runs run
                  JOIN tasks task ON task.id = run.task_id
                 WHERE run.updated_at >= ?
                    OR task.phase IN ('QUEUED', 'ASSIGNED', 'ACCEPTED', 'RUNNING')
                 ORDER BY GREATEST(task.updated_at, run.updated_at), run.id
                 LIMIT ?
                """, (rs, row) -> new TaskStateConsistencySnapshot(
                rs.getObject("task_id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getString("organization_id"), rs.getString("tenant_id"), rs.getString("task_phase"),
                rs.getString("run_status"), rs.getString("manifest_status"), rs.getInt("active_attempt_count"),
                rs.getInt("active_lease_count"), rs.getLong("process_event_count"),
                rs.getLong("max_process_sequence"), rs.getLong("unfinished_subtask_count"),
                JdbcSupport.instant(rs, "observed_at")), JdbcSupport.timestamp(since), limit);
    }

    @Override
    public void upsertIssue(TaskStateConsistencyIssue issue, Instant observedAt) {
        Objects.requireNonNull(issue, "issue");
        Objects.requireNonNull(observedAt, "observedAt");
        jdbc.update("""
                INSERT INTO task_state_consistency_issues
                    (id, task_id, run_id, organization_id, tenant_id, issue_type, task_phase, run_status,
                     manifest_status, detail, status, occurrences, first_seen_at, last_seen_at,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', 1, ?, ?, ?, ?)
                ON CONFLICT (task_id, run_id, issue_type) DO UPDATE SET
                    organization_id = EXCLUDED.organization_id,
                    tenant_id = EXCLUDED.tenant_id,
                    task_phase = EXCLUDED.task_phase,
                    run_status = EXCLUDED.run_status,
                    manifest_status = EXCLUDED.manifest_status,
                    detail = EXCLUDED.detail,
                    status = 'OPEN',
                    occurrences = task_state_consistency_issues.occurrences + 1,
                    last_seen_at = EXCLUDED.last_seen_at,
                    resolved_at = NULL,
                    updated_at = EXCLUDED.updated_at
                """, UUID.randomUUID(), issue.taskId(), issue.runId(), issue.organizationId(), issue.tenantId(),
                issue.type(), issue.taskPhase(), issue.runStatus(), issue.manifestStatus(), issue.detail(),
                JdbcSupport.timestamp(issue.observedAt()), JdbcSupport.timestamp(observedAt),
                JdbcSupport.timestamp(observedAt), JdbcSupport.timestamp(observedAt));
    }

    @Override
    public List<String> findOpenIssueTypes(UUID taskId, UUID runId) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        return jdbc.queryForList("""
                SELECT issue_type FROM task_state_consistency_issues
                 WHERE task_id = ? AND run_id = ? AND status = 'OPEN'
                """, String.class, taskId, runId);
    }

    @Override
    public void resolveIssue(UUID taskId, UUID runId, String type, Instant resolvedAt) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        requireText(type, "type");
        Objects.requireNonNull(resolvedAt, "resolvedAt");
        jdbc.update("""
                UPDATE task_state_consistency_issues
                   SET status = 'RESOLVED', resolved_at = ?, updated_at = ?
                 WHERE task_id = ? AND run_id = ? AND issue_type = ? AND status = 'OPEN'
                """, JdbcSupport.timestamp(resolvedAt), JdbcSupport.timestamp(resolvedAt), taskId, runId, type);
    }

    @Override
    public List<TaskStateConsistencyIssueRecord> findOpenIssues(int limit) {
        validateLimit(limit);
        return jdbc.query("""
                SELECT id, task_id, run_id, organization_id, tenant_id, issue_type, task_phase, run_status,
                       manifest_status, detail, status, occurrences, first_seen_at, last_seen_at, resolved_at
                  FROM task_state_consistency_issues
                 WHERE status = 'OPEN'
                 ORDER BY last_seen_at DESC, id
                 LIMIT ?
                """, (rs, row) -> new TaskStateConsistencyIssueRecord(
                rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getString("organization_id"), rs.getString("tenant_id"),
                rs.getString("issue_type"), rs.getString("task_phase"), rs.getString("run_status"),
                rs.getString("manifest_status"), rs.getString("detail"), rs.getString("status"),
                rs.getInt("occurrences"), JdbcSupport.instant(rs, "first_seen_at"),
                JdbcSupport.instant(rs, "last_seen_at"), nullableInstant(rs, "resolved_at")), limit);
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    private static Instant nullableInstant(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        java.sql.Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
