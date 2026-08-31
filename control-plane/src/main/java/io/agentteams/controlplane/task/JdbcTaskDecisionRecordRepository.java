package io.agentteams.controlplane.task;

import io.agentteams.application.api.TaskEventVisibility;
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

@Repository
public final class JdbcTaskDecisionRecordRepository implements TaskDecisionRecordRepository {
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcTaskDecisionRecordRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcTaskDecisionRecordRepository(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc, "jdbc"); }

    @Override
    public void insert(ExecutionContext context, TaskDecisionRecord record) {
        jdbc.update("""
                INSERT INTO task_decision_records
                    (id, run_id, task_id, visibility, goal_summary, selected_action, evidence_summary,
                     constraints_summary, confidence, created_at)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                 WHERE EXISTS (SELECT 1 FROM task_runs WHERE id = ? AND organization_id = ? AND tenant_id = ?)
                ON CONFLICT (id) DO NOTHING
                """, record.id(), record.runId(), record.taskId(), record.visibility().name(), record.goalSummary(),
                record.selectedAction(), record.evidenceSummary(), record.constraintsSummary(), record.confidence(),
                JdbcSupport.timestamp(record.createdAt()), record.runId(), context.organizationId(), context.tenantId());
    }

    @Override
    public List<TaskDecisionRecord> find(ExecutionContext context, UUID taskId, UUID runId,
            Set<TaskEventVisibility> visible) {
        if (visible.isEmpty()) return List.of();
        List<TaskEventVisibility> levels = visible.stream().sorted().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(levels.size(), "?"));
        String sql = """
                SELECT decision.id, decision.task_id, decision.run_id, decision.visibility,
                       decision.goal_summary, decision.selected_action, decision.evidence_summary,
                       decision.constraints_summary, decision.confidence, decision.created_at
                  FROM task_decision_records decision
                  JOIN task_runs run ON run.id = decision.run_id
                 WHERE run.organization_id = ? AND run.tenant_id = ? AND decision.task_id = ?
                   AND decision.run_id = ? AND decision.visibility IN (""" + placeholders + ") ORDER BY decision.created_at, decision.id";
        List<Object> args = new ArrayList<>(List.of(context.organizationId(), context.tenantId(), taskId, runId));
        levels.forEach(level -> args.add(level.name()));
        return jdbc.query(sql, this::map, args.toArray());
    }

    private TaskDecisionRecord map(ResultSet rs, int row) throws SQLException {
        double confidence = rs.getDouble("confidence");
        return new TaskDecisionRecord(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getObject("run_id", UUID.class), TaskEventVisibility.from(rs.getString("visibility")),
                rs.getString("goal_summary"), rs.getString("selected_action"), rs.getString("evidence_summary"),
                rs.getString("constraints_summary"), rs.wasNull() ? null : confidence, JdbcSupport.instant(rs, "created_at"));
    }
}
