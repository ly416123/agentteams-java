package io.agentteams.controlplane.persistence;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class ArtifactRepository {

    private final JdbcTemplate jdbc;

    ArtifactRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ArtifactRecord artifact) {
        jdbc.update("""
                INSERT INTO artifacts
                    (id, task_id, attempt_id, name, storage_key, content_type, size_bytes,
                     sha256, status, metadata, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, artifact.id(), artifact.taskId(), artifact.attemptId(), artifact.name(),
                artifact.storageKey(), artifact.contentType(), artifact.sizeBytes(), artifact.sha256(),
                artifact.status(), JdbcSupport.json(artifact.metadataJson()),
                JdbcSupport.timestamp(artifact.createdAt()), JdbcSupport.timestamp(artifact.updatedAt()),
                artifact.version());
    }

    public boolean insertIfAbsent(ArtifactRecord artifact) {
        return jdbc.update("""
                INSERT INTO artifacts
                    (id, task_id, attempt_id, name, storage_key, content_type, size_bytes,
                     sha256, status, metadata, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (attempt_id, name, sha256) DO NOTHING
                """, artifact.id(), artifact.taskId(), artifact.attemptId(), artifact.name(),
                artifact.storageKey(), artifact.contentType(), artifact.sizeBytes(), artifact.sha256(),
                artifact.status(), JdbcSupport.json(artifact.metadataJson()),
                JdbcSupport.timestamp(artifact.createdAt()), JdbcSupport.timestamp(artifact.updatedAt()),
                artifact.version()) == 1;
    }

    public Optional<ArtifactRecord> findById(UUID id) {
        return jdbc.query("""
                SELECT id, task_id, attempt_id, name, storage_key, content_type, size_bytes,
                       sha256, status, metadata::text, created_at, updated_at, version
                  FROM artifacts WHERE id = ?
                """, this::map, id).stream().findFirst();
    }

    public List<ArtifactRecord> findByTaskId(UUID taskId) {
        return jdbc.query("""
                SELECT id, task_id, attempt_id, name, storage_key, content_type, size_bytes,
                       sha256, status, metadata::text, created_at, updated_at, version
                  FROM artifacts WHERE task_id = ? ORDER BY created_at, id
        """, this::map, taskId);
    }

    public List<ArtifactRecord> findLatest(int limit) {
        if (limit <= 0 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        return jdbc.query("""
                SELECT id, task_id, attempt_id, name, storage_key, content_type, size_bytes,
                       sha256, status, metadata::text, created_at, updated_at, version
                  FROM artifacts ORDER BY created_at DESC, id DESC LIMIT ?
                """, this::map, limit);
    }

    public List<ArtifactRecord> findByTaskIdAndAttemptId(UUID taskId, UUID attemptId) {
        return jdbc.query("""
                SELECT id, task_id, attempt_id, name, storage_key, content_type, size_bytes,
                       sha256, status, metadata::text, created_at, updated_at, version
                  FROM artifacts WHERE task_id = ? AND attempt_id = ? ORDER BY created_at, id
                """, this::map, taskId, attemptId);
    }

    public Optional<ArtifactRecord> findByAttemptIdNameSha256(UUID attemptId, String name, String sha256) {
        return jdbc.query("""
                SELECT id, task_id, attempt_id, name, storage_key, content_type, size_bytes,
                       sha256, status, metadata::text, created_at, updated_at, version
                  FROM artifacts WHERE attempt_id = ? AND name = ? AND sha256 = ?
                """, this::map, attemptId, name, sha256).stream().findFirst();
    }

    public long countByAttemptId(UUID attemptId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM artifacts WHERE attempt_id = ?", Long.class,
                attemptId);
        return count == null ? 0 : count;
    }

    private ArtifactRecord map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ArtifactRecord(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getObject("attempt_id", UUID.class), rs.getString("name"), rs.getString("storage_key"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getString("status"), rs.getString("metadata"), JdbcSupport.instant(rs, "created_at"),
                JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
