package io.agentteams.controlplane.task;

import io.agentteams.controlplane.persistence.JdbcSupport;
import io.agentteams.controlplane.security.ExecutionContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL task tree projection with run ownership checked through task_runs. */
@Repository
public final class JdbcTaskTreeRepository implements TaskTreeRepository {
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
                SELECT subtask.task_id, subtask.parent_task_id, subtask.sequence, subtask.status,
                       subtask.dependency_ids, subtask.updated_at
                  FROM task_subtasks subtask
                  JOIN task_runs run ON run.id = subtask.run_id
                 WHERE run.organization_id = ? AND run.tenant_id = ? AND subtask.run_id = ?
                 ORDER BY subtask.sequence, subtask.task_id
                """, this::map, context.organizationId(), context.tenantId(), runId);
    }

    private TaskTreeNode map(ResultSet rs, int row) throws SQLException {
        List<UUID> dependencies = JdbcSupport.stringArray(rs.getString("dependency_ids")).stream()
                .map(UUID::fromString).toList();
        UUID parent = rs.getObject("parent_task_id", UUID.class);
        return new TaskTreeNode(rs.getObject("task_id", UUID.class), parent, rs.getLong("sequence"),
                rs.getString("status"), dependencies, JdbcSupport.instant(rs, "updated_at"));
    }
}
