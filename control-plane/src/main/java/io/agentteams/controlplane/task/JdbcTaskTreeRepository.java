package io.agentteams.controlplane.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.persistence.JdbcSupport;
import io.agentteams.controlplane.security.ExecutionContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL task tree projection with run ownership checked through task_runs. */
@Repository
public class JdbcTaskTreeRepository implements TaskTreeRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcTaskTreeRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcTaskTreeRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void upsert(ExecutionContext context, UUID runId, TaskTreeNode node) {
        jdbc.update("""
                INSERT INTO task_subtasks
                    (id, run_id, task_id, parent_task_id, sequence, status, dependency_ids, created_at, updated_at)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?
                 WHERE EXISTS (SELECT 1 FROM task_runs WHERE id = ? AND organization_id = ? AND tenant_id = ?)
                ON CONFLICT (run_id, task_id) DO UPDATE SET parent_task_id = EXCLUDED.parent_task_id,
                    sequence = EXCLUDED.sequence, status = EXCLUDED.status,
                    dependency_ids = EXCLUDED.dependency_ids, updated_at = EXCLUDED.updated_at
                """, node.taskId(), runId, node.taskId(), node.parentTaskId(), node.sequence(), node.status(),
                JdbcSupport.json(JdbcSupport.jsonArray(node.dependencyIds().stream().map(UUID::toString).toList())),
                JdbcSupport.timestamp(node.updatedAt()), JdbcSupport.timestamp(node.updatedAt()), runId,
                context.organizationId(), context.tenantId());
    }

    @Override
    public List<TaskTreeNode> find(ExecutionContext context, UUID runId) {
        return jdbc.query("""
                SELECT subtask.task_id, subtask.parent_task_id, subtask.sequence,
                       COALESCE(CASE task.phase
                           WHEN 'SUCCEEDED' THEN 'SUCCEEDED'
                           WHEN 'FAILED' THEN 'FAILED'
                           WHEN 'REJECTED' THEN 'FAILED'
                           WHEN 'CANCELLED' THEN 'CANCELLED'
                           WHEN 'RUNNING' THEN 'RUNNING'
                           WHEN 'ASSIGNED' THEN 'RUNNING'
                           WHEN 'ACCEPTED' THEN 'RUNNING'
                           WHEN 'WAITING_SUBTASKS' THEN 'RUNNING'
                           ELSE NULL
                       END, subtask.status) AS status,
                       subtask.dependency_ids, subtask.updated_at, task.spec::text AS task_spec
                  FROM task_subtasks subtask
                  JOIN task_runs run ON run.id = subtask.run_id
                  LEFT JOIN tasks task ON task.id = subtask.task_id
                       AND (task.id = run.task_id OR task.parent_task_id = subtask.parent_task_id)
                 WHERE run.organization_id = ? AND run.tenant_id = ? AND subtask.run_id = ?
                 ORDER BY subtask.sequence, subtask.task_id
                """, this::map, context.organizationId(), context.tenantId(), runId);
    }

    @Override
    public List<TaskTreeNode> findLatestForParent(ExecutionContext context, UUID parentTaskId) {
        return jdbc.query("""
                SELECT subtask.task_id, subtask.parent_task_id, subtask.sequence,
                       COALESCE(CASE task.phase
                           WHEN 'SUCCEEDED' THEN 'SUCCEEDED'
                           WHEN 'FAILED' THEN 'FAILED'
                           WHEN 'REJECTED' THEN 'FAILED'
                           WHEN 'CANCELLED' THEN 'CANCELLED'
                           WHEN 'RUNNING' THEN 'RUNNING'
                           WHEN 'ASSIGNED' THEN 'RUNNING'
                           WHEN 'ACCEPTED' THEN 'RUNNING'
                           WHEN 'WAITING_SUBTASKS' THEN 'RUNNING'
                           ELSE NULL
                       END, subtask.status) AS status,
                       subtask.dependency_ids, subtask.updated_at, task.spec::text AS task_spec
                  FROM task_subtasks subtask
                  JOIN task_runs run ON run.id = subtask.run_id
                  LEFT JOIN tasks task ON task.id = subtask.task_id
                       AND (task.id = run.task_id OR task.parent_task_id = subtask.parent_task_id)
                 WHERE run.organization_id = ? AND run.tenant_id = ?
                   AND subtask.parent_task_id = ?
                   AND subtask.run_id = (
                       SELECT candidate.id
                         FROM task_subtasks candidate_subtask
                         JOIN task_runs candidate ON candidate.id = candidate_subtask.run_id
                        WHERE candidate.organization_id = ? AND candidate.tenant_id = ?
                          AND candidate_subtask.parent_task_id = ?
                        ORDER BY candidate.created_at DESC, candidate.id DESC
                        LIMIT 1
                   )
                 ORDER BY subtask.sequence, subtask.task_id
                """, this::map, context.organizationId(), context.tenantId(), parentTaskId,
                context.organizationId(), context.tenantId(), parentTaskId);
    }

    @Override
    public int deleteOthers(ExecutionContext context, UUID runId, Collection<UUID> keepTaskIds) {
        // 豁免根投影行（parent_task_id IS NULL，manager plan 的单根）：声明式清单只管辖子任务。
        if (keepTaskIds.isEmpty()) {
            return jdbc.update("""
                    DELETE FROM task_subtasks subtask
                     USING task_runs run
                     WHERE subtask.run_id = run.id AND subtask.run_id = ?
                       AND subtask.parent_task_id IS NOT NULL
                       AND run.organization_id = ? AND run.tenant_id = ?
                    """, runId, context.organizationId(), context.tenantId());
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(keepTaskIds.size(), "?"));
        Object[] params = new Object[keepTaskIds.size() + 3];
        params[0] = runId;
        int index = 1;
        for (UUID id : keepTaskIds) {
            params[index++] = id;
        }
        params[index] = context.organizationId();
        params[index + 1] = context.tenantId();
        return jdbc.update("""
                DELETE FROM task_subtasks subtask
                 USING task_runs run
                 WHERE subtask.run_id = run.id AND subtask.run_id = ?
                   AND subtask.task_id NOT IN (%s)
                   AND subtask.parent_task_id IS NOT NULL
                   AND run.organization_id = ? AND run.tenant_id = ?
                """.formatted(placeholders), params);
    }

    private TaskTreeNode map(ResultSet rs, int row) throws SQLException {
        List<UUID> dependencies = JdbcSupport.stringArray(rs.getString("dependency_ids")).stream()
                .map(UUID::fromString).toList();
        UUID parent = rs.getObject("parent_task_id", UUID.class);
        return new TaskTreeNode(rs.getObject("task_id", UUID.class), parent, rs.getLong("sequence"),
                rs.getString("status"), dependencies, JdbcSupport.instant(rs, "updated_at"),
                dependencyResultRefs(rs.getString("task_spec"), dependencies));
    }

    /** Expose stable refs only when the dependency gate persisted a non-empty result handoff. */
    private static java.util.Map<UUID, String> dependencyResultRefs(String taskSpec, List<UUID> dependencies) {
        if (taskSpec == null || dependencies.isEmpty()) return java.util.Map.of();
        try {
            JsonNode handedResults = JSON.readTree(taskSpec).path("inputJson").path("dependencyResults");
            java.util.Map<UUID, String> refs = new java.util.LinkedHashMap<>();
            if (handedResults.isArray()) {
                for (JsonNode item : handedResults) {
                    UUID dependencyId = UUID.fromString(item.path("taskId").asText());
                    if (dependencies.contains(dependencyId) && !item.path("result").asText("").isBlank()) {
                        refs.put(dependencyId, "task-result:" + dependencyId);
                    }
                }
            }
            return java.util.Map.copyOf(refs);
        } catch (Exception ignored) {
            return java.util.Map.of();
        }
    }
}
