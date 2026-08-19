package io.agentteams.controlplane.config;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable desired/applied configuration state; runtime Pods never become the source of truth. */
public final class ConfigLifecycleRepository {
    private final JdbcTemplate jdbc;

    public ConfigLifecycleRepository(JdbcTemplate jdbc) { this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc"); }

    public boolean insertFile(ConfigFileRecord file) {
        return jdbc.update("""
                INSERT INTO config_files(id, snapshot_id, path, storage_key, checksum, size_bytes, content_type)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (snapshot_id, path) DO NOTHING
                """, file.id(), file.snapshotId(), file.path(), file.storageKey(), file.checksum(),
                file.sizeBytes(), file.contentType()) == 1;
    }

    public ConfigBindingRecord upsertBinding(ConfigBindingRecord binding) {
        jdbc.update("""
                INSERT INTO config_bindings(id, subject, agent_id, snapshot_id, desired_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (subject, agent_id) DO UPDATE SET snapshot_id = EXCLUDED.snapshot_id,
                    desired_at = EXCLUDED.desired_at
                """, binding.id(), binding.subject(), binding.agentId(), binding.snapshotId(),
                java.sql.Timestamp.from(binding.desiredAt()));
        return binding;
    }

    public Optional<ConfigBindingRecord> findBinding(String subject, UUID agentId) {
        return jdbc.query("""
                SELECT id, subject, agent_id, snapshot_id, desired_at
                  FROM config_bindings WHERE subject = ? AND agent_id = ?
                """, (rs, row) -> new ConfigBindingRecord(rs.getObject("id", UUID.class), rs.getString("subject"),
                        rs.getObject("agent_id", UUID.class), rs.getObject("snapshot_id", UUID.class),
                        instant(rs, "desired_at")), subject, agentId).stream().findFirst();
    }

    public Optional<ConfigBindingRecord> findBinding(UUID bindingId) {
        return jdbc.query("""
                SELECT id, subject, agent_id, snapshot_id, desired_at
                  FROM config_bindings WHERE id = ?
                """, (rs, row) -> new ConfigBindingRecord(rs.getObject("id", UUID.class), rs.getString("subject"),
                rs.getObject("agent_id", UUID.class), rs.getObject("snapshot_id", UUID.class),
                instant(rs, "desired_at")), bindingId).stream().findFirst();
    }

    public Optional<ConfigApplyRecord> findApply(UUID bindingId, UUID snapshotId) {
        return jdbc.query("""
                SELECT id, binding_id, agent_id, snapshot_id, phase, error_message, applied_at, updated_at
                  FROM config_apply_records WHERE binding_id = ? AND snapshot_id = ?
                """, (rs, row) -> new ConfigApplyRecord(rs.getObject("id", UUID.class),
                rs.getObject("binding_id", UUID.class), rs.getObject("agent_id", UUID.class),
                rs.getObject("snapshot_id", UUID.class), rs.getString("phase"), rs.getString("error_message"),
                timestampOrNull(rs, "applied_at"), instant(rs, "updated_at")), bindingId, snapshotId)
                .stream().findFirst();
    }

    public void recordApply(ConfigApplyRecord apply) {
        jdbc.update("""
                INSERT INTO config_apply_records(id, binding_id, agent_id, snapshot_id, phase,
                    error_message, applied_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (binding_id, snapshot_id) DO UPDATE SET phase = EXCLUDED.phase,
                    error_message = EXCLUDED.error_message, applied_at = EXCLUDED.applied_at,
                    updated_at = EXCLUDED.updated_at
                """, apply.id(), apply.bindingId(), apply.agentId(), apply.snapshotId(), apply.phase(),
                apply.errorMessage(), apply.appliedAt() == null ? null : java.sql.Timestamp.from(apply.appliedAt()),
                java.sql.Timestamp.from(apply.updatedAt()));
    }

    public boolean insertUpload(ConfigUploadRecord upload) {
        return jdbc.update("""
                INSERT INTO config_uploads(id, snapshot_id, path, storage_key, content_type,
                    expected_checksum, expected_size_bytes, status, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (snapshot_id, path) DO NOTHING
                """, upload.id(), upload.snapshotId(), upload.path(), upload.storageKey(), upload.contentType(),
                upload.expectedChecksum(), upload.expectedSizeBytes(), upload.status(),
                java.sql.Timestamp.from(upload.createdAt()), java.sql.Timestamp.from(upload.expiresAt())) == 1;
    }

    public Optional<ConfigUploadRecord> findUpload(UUID uploadId) {
        return jdbc.query("""
                SELECT id, snapshot_id, path, storage_key, content_type, expected_checksum,
                    expected_size_bytes, status, created_at, expires_at, completed_at, deleted_at
                  FROM config_uploads WHERE id = ?
                """, ConfigLifecycleRepository::mapUpload, uploadId).stream().findFirst();
    }

    public Optional<ConfigUploadRecord> findUploadBySnapshotAndPath(UUID snapshotId, String path) {
        return jdbc.query("""
                SELECT id, snapshot_id, path, storage_key, content_type, expected_checksum,
                    expected_size_bytes, status, created_at, expires_at, completed_at, deleted_at
                  FROM config_uploads WHERE snapshot_id = ? AND path = ?
                """, ConfigLifecycleRepository::mapUpload, snapshotId, path).stream().findFirst();
    }

    public List<ConfigUploadRecord> findExpiredUploads(Instant now, int limit) {
        return jdbc.query("""
                SELECT id, snapshot_id, path, storage_key, content_type, expected_checksum,
                    expected_size_bytes, status, created_at, expires_at, completed_at, deleted_at
                  FROM config_uploads
                 WHERE status = 'PENDING' AND expires_at <= ?
                 ORDER BY expires_at, id
                 LIMIT ?
                """, ConfigLifecycleRepository::mapUpload, java.sql.Timestamp.from(now), limit);
    }

    public void markUploadCompleted(UUID uploadId, Instant completedAt) {
        jdbc.update("""
                UPDATE config_uploads SET status = 'COMPLETED', completed_at = ?
                 WHERE id = ? AND status = 'PENDING'
                """, java.sql.Timestamp.from(completedAt), uploadId);
    }

    public void markUploadDeleted(UUID uploadId, Instant deletedAt) {
        jdbc.update("""
                UPDATE config_uploads SET status = 'DELETED', deleted_at = ?
                 WHERE id = ? AND status = 'PENDING'
                """, java.sql.Timestamp.from(deletedAt), uploadId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant timestampOrNull(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static ConfigUploadRecord mapUpload(ResultSet rs, int row) throws SQLException {
        return new ConfigUploadRecord(rs.getObject("id", UUID.class), rs.getObject("snapshot_id", UUID.class),
                rs.getString("path"), rs.getString("storage_key"), rs.getString("content_type"),
                rs.getString("expected_checksum"), rs.getLong("expected_size_bytes"), rs.getString("status"),
                instant(rs, "created_at"), instant(rs, "expires_at"), timestampOrNull(rs, "completed_at"),
                timestampOrNull(rs, "deleted_at"));
    }
}
