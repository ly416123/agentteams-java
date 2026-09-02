package io.agentteams.controlplane.schedule;

import io.agentteams.controlplane.persistence.JdbcSupport;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL persistence for one occurrence of a scheduled task. */
public final class JdbcScheduledTaskRunRepository implements ScheduledTaskRunRepository {
    private final JdbcTemplate jdbc;

    public JdbcScheduledTaskRunRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public ScheduledTaskRun insertIfAbsent(ScheduledTaskRun run) {
        try {
            jdbc.update("""
                    INSERT INTO scheduled_task_runs
                        (id, schedule_id, task_id, occurrence_at, status, created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (schedule_id, occurrence_at) DO NOTHING
                    """, run.id(), run.scheduleId(), run.taskId(), JdbcSupport.timestamp(run.occurrenceAt()),
                    run.status().name(), JdbcSupport.timestamp(run.createdAt()), JdbcSupport.timestamp(run.updatedAt()),
                    run.version());
        } catch (DuplicateKeyException error) {
            throw new IllegalArgumentException("scheduled run identity already exists", error);
        }
        return findById(run.id()).orElseGet(() -> jdbc.query("""
                SELECT id FROM scheduled_task_runs WHERE schedule_id = ? AND occurrence_at = ?
                """, (rs, row) -> rs.getObject(1, UUID.class), run.scheduleId(),
                JdbcSupport.timestamp(run.occurrenceAt())).stream().findFirst()
                .flatMap(this::findById).orElseThrow(() -> new IllegalStateException("scheduled run disappeared")));
    }

    @Override
    public List<ScheduledTaskRun> list(ScheduledTaskScope scope, UUID scheduleId, int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be between 1 and 500");
        return query(baseSql() + " WHERE s.organization_id = ? AND s.tenant_id = ? AND "
                + "(s.project_id = ? OR (s.project_id IS NULL AND ? IS NULL)) AND r.schedule_id = ? "
                + "ORDER BY r.occurrence_at DESC, r.id DESC LIMIT ?", scope, scheduleId, limit);
    }

    @Override
    public Optional<ScheduledTaskRun> find(ScheduledTaskScope scope, UUID scheduleId, UUID runId) {
        return query(baseSql() + " WHERE s.organization_id = ? AND s.tenant_id = ? AND "
                + "(s.project_id = ? OR (s.project_id IS NULL AND ? IS NULL)) AND r.schedule_id = ? AND r.id = ?",
                scope, scheduleId, runId).stream().findFirst();
    }

    @Override
    public ScheduledTaskRun cancel(ScheduledTaskScope scope, UUID scheduleId, UUID runId,
            String operationKey, Instant at) {
        if (operationKey == null || operationKey.isBlank()) throw new IllegalArgumentException("operationKey is required");
        int updated = jdbc.update("""
                UPDATE scheduled_task_runs r SET status = 'CANCELLED', cancel_operation_key = ?,
                    updated_at = ?, version = version + 1
                  FROM scheduled_tasks s
                 WHERE r.id = ? AND r.schedule_id = s.id AND s.organization_id = ? AND s.tenant_id = ?
                   AND (s.project_id = ? OR (s.project_id IS NULL AND ? IS NULL))
                   AND r.status IN ('TRIGGERED', 'RUNNING', 'RECOVERY_REQUIRED')
                """, operationKey, JdbcSupport.timestamp(at), runId, scope.organizationId(), scope.tenantId(),
                scope.projectId(), scope.projectId());
        ScheduledTaskRun result = find(scope, scheduleId, runId).orElseThrow(ScheduledTaskNotFoundException::new);
        if (updated == 0 && result.status() != ScheduledTaskRun.Status.CANCELLED) {
            throw new IllegalStateException("scheduled run is no longer active");
        }
        return result;
    }

    private String baseSql() {
        return """
                SELECT r.id, r.schedule_id, r.task_id, r.occurrence_at,
                       CASE WHEN r.status = 'CANCELLED' OR t.phase = 'CANCELLED' THEN 'CANCELLED'
                            WHEN t.phase = 'SUCCEEDED' THEN 'SUCCEEDED'
                            WHEN t.phase = 'FAILED' THEN 'FAILED'
                            WHEN tr.status = 'RUNNING' THEN 'RUNNING'
                            WHEN tr.status = 'SUCCEEDED' THEN 'SUCCEEDED'
                            WHEN tr.status = 'FAILED' THEN 'FAILED'
                            ELSE r.status END AS status,
                       r.created_at, r.updated_at,
                       r.version, t.phase AS task_phase, tr.id AS execution_run_id, tr.status AS run_status,
                       rm.status AS result_status, rm.summary AS result_summary
                  FROM scheduled_task_runs r
                  JOIN scheduled_tasks s ON s.id = r.schedule_id
                  JOIN tasks t ON t.id = r.task_id
                  LEFT JOIN LATERAL (SELECT id, status FROM task_runs WHERE task_id = r.task_id
                                     ORDER BY created_at DESC, id DESC LIMIT 1) tr ON TRUE
                  LEFT JOIN task_result_manifests rm ON rm.run_id = tr.id
                """;
    }

    private List<ScheduledTaskRun> query(String sql, ScheduledTaskScope scope, Object... extra) {
        Object[] args = new Object[4 + extra.length];
        args[0] = scope.organizationId(); args[1] = scope.tenantId(); args[2] = scope.projectId(); args[3] = scope.projectId();
        System.arraycopy(extra, 0, args, 4, extra.length);
        return jdbc.query(sql, (rs, row) -> map(rs), args);
    }

    private Optional<ScheduledTaskRun> findById(UUID id) {
        return jdbc.query(baseSql() + " WHERE r.id = ?", (rs, row) -> map(rs), id).stream().findFirst();
    }

    private ScheduledTaskRun map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ScheduledTaskRun(rs.getObject("id", UUID.class), rs.getObject("schedule_id", UUID.class),
                rs.getObject("task_id", UUID.class), rs.getObject("execution_run_id", UUID.class),
                JdbcSupport.instant(rs, "occurrence_at"), status(rs.getString("status")),
                rs.getString("task_phase"), rs.getString("result_status"), rs.getString("result_summary"),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static ScheduledTaskRun.Status status(String stored) {
        return switch (stored) {
            case "TRIGGERED" -> ScheduledTaskRun.Status.TRIGGERED;
            case "RUNNING" -> ScheduledTaskRun.Status.RUNNING;
            case "SUCCEEDED" -> ScheduledTaskRun.Status.SUCCEEDED;
            case "FAILED" -> ScheduledTaskRun.Status.FAILED;
            case "CANCELLED" -> ScheduledTaskRun.Status.CANCELLED;
            case "RECOVERY_REQUIRED" -> ScheduledTaskRun.Status.RECOVERY_REQUIRED;
            default -> throw new IllegalArgumentException("unknown scheduled run status: " + stored);
        };
    }
}
