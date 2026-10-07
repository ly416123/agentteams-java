package io.agentteams.controlplane.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.persistence.JdbcSupport;
import io.agentteams.controlplane.security.ExecutionContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
public class JdbcTaskRunObservationRepository implements TaskRunObservationRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
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
    public Optional<TaskRunSnapshot> snapshotForRun(ExecutionContext context, UUID taskId, UUID runId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        return jdbc.query("""
                SELECT task.phase AS task_phase, task.spec::text AS task_spec,
                       run.status AS run_status, run.started_at, run.completed_at
                  FROM tasks task
                  JOIN resource_scopes scope ON scope.resource_type = 'TASK' AND scope.resource_id = task.id
                  LEFT JOIN legacy_tenant_mappings mapping ON mapping.legacy_tenant_key = scope.tenant_id
                  LEFT JOIN task_runs run ON run.task_id = task.id AND run.id = ?
                       AND run.organization_id = ? AND run.tenant_id = ?
                 WHERE task.id = ?
                   AND COALESCE(mapping.organization_id::text, 'legacy:' || scope.tenant_id) = ?
                   AND COALESCE(mapping.tenant_id::text, scope.tenant_id) = ?
                """, (rs, row) -> {
            JsonNode spec = parseSpec(rs.getString("task_spec"));
            return new TaskRunSnapshot(rs.getString("task_phase"), rs.getString("run_status"),
                    nullableInstant(rs, "started_at"), nullableInstant(rs, "completed_at"),
                    textOr(spec, "taskType", "unknown"), textOrNull(spec, "sourceConversationId"));
        }, runId, context.organizationId(), context.tenantId(), taskId, context.organizationId(), context.tenantId())
                .stream().findFirst();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static JsonNode parseSpec(String value) {
        try {
            JsonNode parsed = JSON.readTree(value);
            return parsed != null && parsed.isObject() ? parsed : JSON.createObjectNode();
        } catch (java.io.IOException error) {
            return JSON.createObjectNode();
        }
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = textOrNull(node, field);
        return value == null ? fallback : value;
    }

    private static String textOrNull(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public void ensureRun(ExecutionContext context, UUID taskId, UUID runId, String status, Instant at) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(at, "at");
        int updated = jdbc.update("""
                INSERT INTO task_runs
                    (id, task_id, organization_id, tenant_id, status, started_at, completed_at,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT (id) DO UPDATE SET
                    status = CASE
                        WHEN task_runs.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') THEN task_runs.status
                        WHEN task_runs.status = 'RUNNING' AND EXCLUDED.status = 'QUEUED' THEN task_runs.status
                        ELSE EXCLUDED.status
                    END,
                    started_at = COALESCE(task_runs.started_at, EXCLUDED.started_at),
                    completed_at = CASE
                        WHEN task_runs.completed_at IS NOT NULL THEN task_runs.completed_at
                        WHEN EXCLUDED.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') THEN EXCLUDED.updated_at
                        ELSE task_runs.completed_at
                    END,
                    updated_at = GREATEST(task_runs.updated_at, EXCLUDED.updated_at),
                    version = task_runs.version + 1
                 WHERE task_runs.task_id = EXCLUDED.task_id
                   AND task_runs.organization_id = EXCLUDED.organization_id
                   AND task_runs.tenant_id = EXCLUDED.tenant_id
                """, runId, taskId, context.organizationId(), context.tenantId(), status,
                "RUNNING".equals(status) ? JdbcSupport.timestamp(at) : null,
                terminal(status) ? JdbcSupport.timestamp(at) : null,
                JdbcSupport.timestamp(at), JdbcSupport.timestamp(at));
        if (updated == 0) {
            throw identityMismatch(runId, taskId);
        }
    }

    private IllegalArgumentException identityMismatch(UUID runId, UUID taskId) {
        var existing = jdbc.queryForMap("SELECT task_id, organization_id, tenant_id FROM task_runs WHERE id = ?", runId);
        if (!taskId.equals(existing.get("task_id"))) {
            return new IllegalArgumentException("run task does not match: " + runId);
        }
        return new IllegalArgumentException("run scope does not match: " + runId);
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

    @Override
    public Optional<UUID> latestRunId(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        return jdbc.query("""
                SELECT id FROM task_runs WHERE task_id = ? ORDER BY created_at DESC LIMIT 1
                """, (rs, row) -> rs.getObject("id", UUID.class), taskId).stream().findFirst();
    }

    private static boolean terminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }
}
