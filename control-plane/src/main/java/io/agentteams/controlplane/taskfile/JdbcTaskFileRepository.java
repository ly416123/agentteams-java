package io.agentteams.controlplane.taskfile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Thin JDBC ledger for G05 task files; SQL semantics covered by kind/L5 acceptance. */
@Repository
public final class JdbcTaskFileRepository {

    private final JdbcTemplate jdbc;

    public JdbcTaskFileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insert(TaskFileRecord record) {
        return jdbc.update("""
                INSERT INTO task_files (id, task_id, attempt_id, role, name, content_type,
                        size_bytes, sha256, storage_key, source_session_id, source_file_id,
                        status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.id(), record.taskId(), record.attemptId(), record.role(), record.name(),
                record.contentType(), record.sizeBytes(), record.sha256(), record.storageKey(),
                record.sourceSessionId(), record.sourceFileId(), record.status(),
                record.createdAt(), record.updatedAt()) == 1;
    }

    public Optional<TaskFileRecord> findById(UUID id) {
        return jdbc.query("SELECT * FROM task_files WHERE id = ?", mapper(), id).stream().findFirst();
    }

    public List<TaskFileRecord> findByTask(UUID taskId, String role) {
        if (role == null || role.isBlank()) {
            return jdbc.query("SELECT * FROM task_files WHERE task_id = ? ORDER BY created_at, id",
                    mapper(), taskId);
        }
        return jdbc.query("SELECT * FROM task_files WHERE task_id = ? AND role = ? ORDER BY created_at, id",
                mapper(), taskId, role);
    }

    /** Dedup lookup for re-uploads with identical content (spec §5.1). */
    public Optional<TaskFileRecord> findDedup(UUID taskId, String role, String name, String sha256) {
        return jdbc.query("SELECT * FROM task_files WHERE task_id = ? AND role = ? AND name = ? AND sha256 = ?",
                mapper(), taskId, role, name, sha256).stream().findFirst();
    }

    /** Reconciliation scan: AVAILABLE OUTPUT rows in insertion order, bounded. */
    public List<TaskFileRecord> findAvailableOutputs(int limit) {
        return jdbc.query("""
                SELECT * FROM task_files WHERE role = 'OUTPUT' AND status = 'AVAILABLE'
                ORDER BY created_at, id LIMIT ?
                """, mapper(), limit);
    }

    public boolean markMissing(UUID id, Instant at) {
        return jdbc.update("UPDATE task_files SET status = 'MISSING', updated_at = ? WHERE id = ?", at, id) == 1;
    }

    /** Exposed for the unit test; production callers use the query methods above. */
    RowMapper<TaskFileRecord> recordMapper() {
        return mapper();
    }

    private RowMapper<TaskFileRecord> mapper() {
        return (ResultSet rs, int rowNum) -> new TaskFileRecord(
                rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getObject("attempt_id", UUID.class), rs.getString("role"), rs.getString("name"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getString("storage_key"), rs.getObject("source_session_id", UUID.class),
                rs.getObject("source_file_id", UUID.class), rs.getString("status"),
                rs.getObject("created_at", Instant.class), rs.getObject("updated_at", Instant.class));
    }
}
