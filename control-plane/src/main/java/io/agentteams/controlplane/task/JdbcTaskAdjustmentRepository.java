package io.agentteams.controlplane.task;

import io.agentteams.controlplane.persistence.JdbcSupport;
import io.agentteams.controlplane.persistence.TaskAdjustmentRecord;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** 补充要求持久化（G02 D5）。由 FoundationTransaction 在事务内持有。 */
public final class JdbcTaskAdjustmentRepository {

    private final JdbcTemplate jdbc;

    public JdbcTaskAdjustmentRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public void insert(TaskAdjustmentRecord record) {
        jdbc.update("""
                INSERT INTO task_adjustments
                    (id, task_id, content, actor, source, consumed_run_id, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, record.id(), record.taskId(), JdbcSupport.json(record.contentJson()), record.actor(),
                record.source(), record.consumedRunId(), JdbcSupport.timestamp(record.createdAt()),
                JdbcSupport.timestamp(record.updatedAt()), record.version());
    }

    public Optional<TaskAdjustmentRecord> findById(UUID id) {
        return jdbc.query("""
                SELECT id, task_id, content::text, actor, source, consumed_run_id,
                       created_at, updated_at, version
                  FROM task_adjustments WHERE id = ?
                """, this::map, id).stream().findFirst();
    }

    public List<TaskAdjustmentRecord> findByTask(UUID taskId, int limit) {
        return jdbc.query("""
                SELECT id, task_id, content::text, actor, source, consumed_run_id,
                       created_at, updated_at, version
                  FROM task_adjustments WHERE task_id = ?
                 ORDER BY created_at ASC, id ASC
                 LIMIT ?
                """, this::map, taskId, limit);
    }

    public List<TaskAdjustmentRecord> pendingByTask(UUID taskId) {
        return jdbc.query("""
                SELECT id, task_id, content::text, actor, source, consumed_run_id,
                       created_at, updated_at, version
                  FROM task_adjustments
                 WHERE task_id = ? AND consumed_run_id IS NULL
                 ORDER BY created_at ASC, id ASC
                """, this::map, taskId);
    }

    /** 将调整标记为已并入指定执行；返回更新行数。 */
    public int markConsumed(List<UUID> ids, UUID runId, java.time.Instant at) {
        Objects.requireNonNull(runId, "runId");
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int updated = 0;
        for (UUID id : ids) {
            updated += jdbc.update("""
                    UPDATE task_adjustments
                       SET consumed_run_id = ?, updated_at = ?, version = version + 1
                     WHERE id = ? AND consumed_run_id IS NULL
                    """, runId, JdbcSupport.timestamp(at), id);
        }
        return updated;
    }

    private TaskAdjustmentRecord map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new TaskAdjustmentRecord(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getString("content"), rs.getString("actor"), rs.getString("source"),
                rs.getObject("consumed_run_id", UUID.class), JdbcSupport.instant(rs, "created_at"),
                JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
