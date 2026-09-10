package io.agentteams.controlplane.task;

import io.agentteams.controlplane.persistence.JdbcSupport;
import io.agentteams.controlplane.persistence.OptimisticLockFailure;
import io.agentteams.controlplane.persistence.TaskResultVersionRecord;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 业务结果版本持久化（G02 D1-D4）。由 FoundationTransaction 在事务内持有。
 * seq 通过「SELECT max(seq) FOR UPDATE」原子递增，由 Service 层保证同 task 串行。
 */
public final class JdbcTaskResultVersionRepository {

    private final JdbcTemplate jdbc;

    public JdbcTaskResultVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public void insert(TaskResultVersionRecord record) {
        jdbc.update("""
                INSERT INTO task_result_versions
                    (id, task_id, run_id, manifest_id, subtask_id, seq, status, summary, content,
                     submitted_by, submitted_at, review_actor, review_comment, reviewed_at,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, record.id(), record.taskId(), record.runId(), record.manifestId(), record.subtaskId(),
                record.seq(), record.status(), record.summary(),
                record.contentJson() == null ? null : JdbcSupport.json(record.contentJson()),
                record.submittedBy(), JdbcSupport.timestamp(record.submittedAt()), record.reviewActor(),
                record.reviewComment(),
                record.reviewedAt() == null ? null : JdbcSupport.timestamp(record.reviewedAt()),
                JdbcSupport.timestamp(record.createdAt()), JdbcSupport.timestamp(record.updatedAt()),
                record.version());
    }

    public Optional<TaskResultVersionRecord> findById(UUID id) {
        return jdbc.query("""
                SELECT id, task_id, run_id, manifest_id, subtask_id, seq, status, summary, content::text,
                       submitted_by, submitted_at, review_actor, review_comment, reviewed_at,
                       created_at, updated_at, version
                  FROM task_result_versions WHERE id = ?
                """, this::map, id).stream().findFirst();
    }

    /** 全部版本（seq 倒序）；打回历史对评审者可见。 */
    public List<TaskResultVersionRecord> findByTask(UUID taskId, int limit) {
        return jdbc.query("""
                SELECT id, task_id, run_id, manifest_id, subtask_id, seq, status, summary, content::text,
                       submitted_by, submitted_at, review_actor, review_comment, reviewed_at,
                       created_at, updated_at, version
                  FROM task_result_versions WHERE task_id = ? AND subtask_id IS NULL
                 ORDER BY seq DESC
                 LIMIT ?
                """, this::map, taskId, limit);
    }

    public Optional<TaskResultVersionRecord> findByManifest(UUID manifestId) {
        return jdbc.query("""
                SELECT id, task_id, run_id, manifest_id, subtask_id, seq, status, summary, content::text,
                       submitted_by, submitted_at, review_actor, review_comment, reviewed_at,
                       created_at, updated_at, version
                  FROM task_result_versions WHERE manifest_id = ?
                """, this::map, manifestId).stream().findFirst();
    }

    /** D2 幂等锚点：同一 run 只提交一个业务结果版本。 */
    public Optional<TaskResultVersionRecord> findByRun(UUID runId) {
        return jdbc.query("""
                SELECT id, task_id, run_id, manifest_id, subtask_id, seq, status, summary, content::text,
                       submitted_by, submitted_at, review_actor, review_comment, reviewed_at,
                       created_at, updated_at, version
                  FROM task_result_versions WHERE run_id = ?
                """, this::map, runId).stream().findFirst();
    }

    public Optional<Integer> latestSeq(UUID taskId) {
        // max(seq) 对空任务返回一行 NULL：extractor 统一把无行/NULL 归一为 empty，
        // 避免 stream findFirst 包装 null 元素抛 NPE。
        return jdbc.query("SELECT max(seq) AS latest FROM task_result_versions WHERE task_id = ?", rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            int value = rs.getInt("latest");
            return rs.wasNull() ? Optional.empty() : Optional.of(value);
        }, taskId);
    }

    public TaskResultVersionRecord updateReview(UUID id, String status, String reviewActor, String reviewComment,
            java.time.Instant reviewedAt, long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE task_result_versions
                   SET status = ?, review_actor = ?, review_comment = ?, reviewed_at = ?,
                       updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, status, reviewActor, reviewComment, JdbcSupport.timestamp(reviewedAt),
                JdbcSupport.timestamp(reviewedAt), id, expectedVersion);
        if (updated == 0) {
            long actual = jdbc.query("SELECT version FROM task_result_versions WHERE id = ?",
                    (rs, row) -> rs.getLong(1), id).stream().findFirst().orElse(-1L);
            throw new OptimisticLockFailure("task_result_version", id, expectedVersion, actual);
        }
        return findById(id).orElseThrow();
    }

    private TaskResultVersionRecord map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        // reviewed_at/review_actor/review_comment 在未评审时为 NULL（JdbcSupport.instant 不可接收 null）
        java.sql.Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
        return new TaskResultVersionRecord(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getObject("manifest_id", UUID.class),
                rs.getObject("subtask_id", UUID.class), rs.getInt("seq"), rs.getString("status"),
                rs.getString("summary"), rs.getString("content"), rs.getString("submitted_by"),
                JdbcSupport.instant(rs, "submitted_at"), rs.getString("review_actor"),
                rs.getString("review_comment"), reviewedAt == null ? null : reviewedAt.toInstant(),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"),
                rs.getLong("version"));
    }
}
