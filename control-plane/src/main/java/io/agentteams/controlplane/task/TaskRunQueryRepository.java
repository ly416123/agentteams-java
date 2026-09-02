package io.agentteams.controlplane.task;

import io.agentteams.controlplane.persistence.JdbcSupport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only task-run projection used by the management console. */
@Repository
public final class TaskRunQueryRepository {
    private final JdbcTemplate jdbc;

    @Autowired
    public TaskRunQueryRepository(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    TaskRunQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TaskRunRecord> findByTaskId(UUID taskId) {
        return jdbc.query("""
                SELECT run.id, run.task_id, run.status, run.started_at, run.completed_at,
                       run.created_at, run.updated_at, run.version,
                       manifest.status AS result_status, manifest.summary AS result_summary
                  FROM task_runs run
                  LEFT JOIN task_result_manifests manifest ON manifest.run_id = run.id
                 WHERE run.task_id = ?
                 ORDER BY run.created_at DESC, run.id DESC
                """, (rs, row) -> new TaskRunRecord(rs.getObject("id", UUID.class),
                rs.getObject("task_id", UUID.class), rs.getString("status"),
                instant(rs, "started_at"), instant(rs, "completed_at"),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"),
                rs.getLong("version"), rs.getString("result_status"), rs.getString("result_summary")), taskId);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record TaskRunRecord(UUID id, UUID taskId, String status, Instant startedAt, Instant completedAt,
            Instant createdAt, Instant updatedAt, long version, String resultStatus, String resultSummary) { }
}
