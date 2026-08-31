package io.agentteams.controlplane.task;

import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskProcessEvent;
import io.agentteams.controlplane.persistence.JdbcSupport;
import io.agentteams.controlplane.security.ExecutionContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL projection for task-process facts with tenant-qualified replay. */
@Repository
public class JdbcTaskProcessEventRepository implements TaskProcessEventRepository {
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcTaskProcessEventRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcTaskProcessEventRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean insert(ExecutionContext context, TaskProcessEvent event) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(event, "event");
        return jdbc.update("""
                INSERT INTO task_process_events
                    (id, task_id, run_id, organization_id, tenant_id, sequence, event_type,
                     visibility, occurred_at, correlation_id, payload, payload_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, event.eventId(), event.taskId(), event.runId(), context.organizationId(), context.tenantId(),
                event.sequence(), event.eventType(), event.visibility().name(), JdbcSupport.timestamp(event.occurredAt()),
                event.correlationId(), event.payload(), event.payloadRef()) == 1;
    }

    @Override
    public List<TaskProcessEvent> find(ExecutionContext context, UUID taskId, UUID runId, long after,
            Set<TaskEventVisibility> visible, int limit) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(visible, "visible");
        if (after < 0) throw new IllegalArgumentException("after must not be negative");
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        if (visible.isEmpty()) return List.of();
        List<TaskEventVisibility> levels = visible.stream().sorted().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(levels.size(), "?"));
        String sql = """
                SELECT id, task_id, run_id, sequence, event_type, visibility, occurred_at,
                       correlation_id, payload, payload_ref
                  FROM task_process_events
                 WHERE organization_id = ? AND tenant_id = ? AND task_id = ? AND run_id = ?
                   AND sequence > ? AND visibility IN (""" + placeholders + ") ORDER BY sequence LIMIT ?";
        List<Object> arguments = new ArrayList<>(List.of(context.organizationId(), context.tenantId(), taskId, runId, after));
        levels.forEach(level -> arguments.add(level.name()));
        arguments.add(limit);
        return jdbc.query(sql, this::map, arguments.toArray());
    }

    private TaskProcessEvent map(ResultSet rs, int row) throws SQLException {
        return new TaskProcessEvent(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getLong("sequence"), rs.getString("event_type"),
                TaskEventVisibility.from(rs.getString("visibility")), JdbcSupport.instant(rs, "occurred_at"),
                rs.getString("correlation_id"), rs.getString("payload"), rs.getString("payload_ref"));
    }
}
