package io.agentteams.controlplane.task;

import io.agentteams.controlplane.persistence.JdbcSupport;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTaskRecoveryCheckpointRepository implements TaskRecoveryCheckpointRepository {
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcTaskRecoveryCheckpointRepository(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    public JdbcTaskRecoveryCheckpointRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public TaskRecoveryCheckpoint save(TaskRecoveryCheckpoint checkpoint) {
        jdbc.update("""
                INSERT INTO task_recovery_checkpoints
                    (id, task_id, run_id, attempt_id, step_key, idempotency_key, status, checkpoint_ref,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id, step_key) DO UPDATE SET
                    status = EXCLUDED.status,
                    checkpoint_ref = EXCLUDED.checkpoint_ref, updated_at = EXCLUDED.updated_at,
                    version = task_recovery_checkpoints.version + 1
                  WHERE task_recovery_checkpoints.idempotency_key = EXCLUDED.idempotency_key
                """, checkpoint.id(), checkpoint.taskId(), checkpoint.runId(), checkpoint.attemptId(),
                checkpoint.stepKey(), checkpoint.idempotencyKey(), checkpoint.status(), checkpoint.checkpointRef(),
                JdbcSupport.timestamp(checkpoint.createdAt()), JdbcSupport.timestamp(checkpoint.updatedAt()),
                checkpoint.version());
        return findById(checkpoint.id()).orElseGet(() -> findByRun(checkpoint.runId()).stream()
                .filter(value -> value.stepKey().equals(checkpoint.stepKey())).findFirst()
                .map(existing -> {
                    if (!existing.idempotencyKey().equals(checkpoint.idempotencyKey())) {
                        throw new IllegalArgumentException("checkpoint idempotency key conflict");
                    }
                    return existing;
                })
                .orElseThrow(() -> new IllegalStateException("checkpoint disappeared")));
    }

    @Override
    public List<TaskRecoveryCheckpoint> findByRun(UUID runId) {
        return jdbc.query(select() + " WHERE run_id = ? ORDER BY updated_at DESC, id DESC", this::map, runId);
    }

    @Override
    public Optional<TaskRecoveryCheckpoint> findLatestByTask(UUID taskId) {
        return jdbc.query(select() + " WHERE task_id = ? AND status = 'COMPLETED'"
                + " ORDER BY updated_at DESC, id DESC LIMIT 1", this::map, taskId).stream().findFirst();
    }

    private Optional<TaskRecoveryCheckpoint> findById(UUID id) {
        return jdbc.query(select() + " WHERE id = ?", this::map, id).stream().findFirst();
    }

    private static String select() {
        return """
                SELECT id, task_id, run_id, attempt_id, step_key, idempotency_key, status, checkpoint_ref,
                       created_at, updated_at, version
                  FROM task_recovery_checkpoints
                """;
    }

    private TaskRecoveryCheckpoint map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new TaskRecoveryCheckpoint(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getObject("attempt_id", UUID.class), rs.getString("step_key"),
                rs.getString("idempotency_key"), rs.getString("status"), rs.getString("checkpoint_ref"),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
