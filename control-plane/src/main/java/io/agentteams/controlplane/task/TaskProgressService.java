package io.agentteams.controlplane.task;

import io.agentteams.application.api.TaskProgressSnapshot;
import io.agentteams.controlplane.security.ExecutionContext;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Builds a progress projection from authoritative subtask rows; it owns no task state transitions. */
@Service
public final class TaskProgressService {
    private final JdbcTemplate jdbc;

    @Autowired
    public TaskProgressService(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public TaskProgressService(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public TaskProgressSnapshot snapshot(ExecutionContext context, UUID taskId, UUID runId, String phase) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        String normalizedPhase = phase == null || phase.isBlank() ? "EXECUTION" : phase.trim();
        ProgressCounts counts = jdbc.query("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE subtask.status = 'SUCCEEDED') AS completed,
                       COUNT(*) FILTER (WHERE subtask.status = 'BLOCKED') AS blocked
                  FROM task_subtasks subtask
                  JOIN task_runs run ON run.id = subtask.run_id
                 WHERE run.organization_id = ? AND run.tenant_id = ?
                   AND subtask.task_id = ? AND subtask.run_id = ?
                """, rs -> rs.next() ? new ProgressCounts(rs.getLong("completed"), rs.getLong("total"),
                        rs.getLong("blocked")) : new ProgressCounts(0, 0, 0),
                context.organizationId(), context.tenantId(), taskId, runId);
        int percent = counts.total() == 0 ? 0 : (int) Math.min(100, counts.completed() * 100 / counts.total());
        String waitingReason = counts.blocked() == 0 ? "" : "blocked subtasks require attention";
        return new TaskProgressSnapshot(normalizedPhase, counts.completed(), counts.total(), percent, waitingReason);
    }

    static record ProgressCounts(long completed, long total, long blocked) { }
}
