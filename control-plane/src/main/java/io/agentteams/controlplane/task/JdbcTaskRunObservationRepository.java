package io.agentteams.controlplane.task;

import io.agentteams.controlplane.persistence.JdbcSupport;
import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL persistence for the run identity and monotonic process cursor. */
@Repository
public final class JdbcTaskRunObservationRepository implements TaskRunObservationRepository {
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcTaskRunObservationRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcTaskRunObservationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<ExecutionContext> contextForTask(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        return jdbc.query("""
                SELECT COALESCE(mapping.organization_id::text, 'legacy:' || scope.tenant_id) AS organization_id,
                       COALESCE(mapping.tenant_id::text, scope.tenant_id) AS tenant_id,
                       scope.project_id, scope.team
                  FROM resource_scopes scope
                  LEFT JOIN legacy_tenant_mappings mapping ON mapping.legacy_tenant_key = scope.tenant_id
                 WHERE scope.resource_type = 'TASK' AND scope.resource_id = ?
                """, (rs, row) -> new ExecutionContext(rs.getString("organization_id"),
                rs.getString("tenant_id"), rs.getString("project_id"), rs.getString("team"), "agent-worker"),
                taskId).stream().findFirst();
    }

    @Override
    public Optional<TaskPlanningSnapshot> planningForTask(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        return jdbc.query("""
                SELECT title, description, source, spec::text AS spec_json
                  FROM tasks
                 WHERE id = ?
                """, (rs, row) -> new TaskPlanningSnapshot(rs.getString("title"), rs.getString("description"),
                rs.getString("source"), rs.getString("spec_json")), taskId).stream().findFirst();
    }

    @Override
    public void ensureRun(ExecutionContext context, UUID taskId, UUID runId, String status, Instant at) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(at, "at");
        jdbc.update("""
                INSERT INTO task_runs
                    (id, task_id, organization_id, tenant_id, status, started_at, completed_at,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT (id) DO UPDATE SET
                    status = EXCLUDED.status,
                    started_at = COALESCE(task_runs.started_at, EXCLUDED.started_at),
                    completed_at = CASE WHEN EXCLUDED.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                                        THEN EXCLUDED.updated_at ELSE task_runs.completed_at END,
                    updated_at = EXCLUDED.updated_at,
                    version = task_runs.version + 1
                """, runId, taskId, context.organizationId(), context.tenantId(), status,
                "RUNNING".equals(status) ? JdbcSupport.timestamp(at) : null,
                terminal(status) ? JdbcSupport.timestamp(at) : null,
                JdbcSupport.timestamp(at), JdbcSupport.timestamp(at));
    }

    @Override
    public long nextSequence(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        jdbc.query("SELECT id FROM task_runs WHERE id = ? FOR UPDATE", (rs, row) -> rs.getObject("id", UUID.class),
                runId).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("task run does not exist: " + runId));
        Long next = jdbc.queryForObject("SELECT COALESCE(MAX(sequence), -1) + 1 FROM task_process_events WHERE run_id = ?",
                Long.class, runId);
        return next == null ? 0 : next;
    }

    private static boolean terminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }
}
