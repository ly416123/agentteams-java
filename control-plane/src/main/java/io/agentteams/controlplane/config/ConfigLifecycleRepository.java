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

    public List<ConfigFileRecord> findFiles(UUID snapshotId) {
        return jdbc.query("""
                SELECT id, snapshot_id, path, storage_key, checksum, size_bytes, content_type
                  FROM config_files WHERE snapshot_id = ? ORDER BY path
                """, (rs, row) -> new ConfigFileRecord(rs.getObject("id", UUID.class),
                rs.getObject("snapshot_id", UUID.class), rs.getString("path"), rs.getString("storage_key"),
                rs.getString("checksum"), rs.getLong("size_bytes"), rs.getString("content_type")), snapshotId);
    }

    public Optional<ConfigFileRecord> findFile(UUID snapshotId, String path) {
        return jdbc.query("""
                SELECT id, snapshot_id, path, storage_key, checksum, size_bytes, content_type
                  FROM config_files WHERE snapshot_id = ? AND path = ?
                """, (rs, row) -> new ConfigFileRecord(rs.getObject("id", UUID.class),
                rs.getObject("snapshot_id", UUID.class), rs.getString("path"), rs.getString("storage_key"),
                rs.getString("checksum"), rs.getLong("size_bytes"), rs.getString("content_type")),
                snapshotId, path).stream().findFirst();
    }

    public List<UUID> findCleanupSnapshotIds(int keepCount, int limit) {
        if (keepCount <= 0) throw new IllegalArgumentException("keepCount must be positive");
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        return jdbc.query("""
                WITH ranked AS (
                    SELECT id, row_number() OVER (PARTITION BY subject ORDER BY version DESC) AS rank
                      FROM config_snapshots
                )
                SELECT ranked.id
                  FROM ranked
                 WHERE ranked.rank > ?
                   AND NOT EXISTS (SELECT 1 FROM config_bindings b WHERE b.snapshot_id = ranked.id)
                   AND NOT EXISTS (SELECT 1 FROM config_uploads u
                                    WHERE u.snapshot_id = ranked.id AND u.status = 'PENDING')
                 ORDER BY ranked.id
                 LIMIT ?
                """, (rs, row) -> rs.getObject(1, UUID.class), keepCount, limit);
    }

    public List<String> findObjectKeys(UUID snapshotId) {
        return jdbc.query("""
                SELECT storage_key FROM config_files WHERE snapshot_id = ?
                UNION
                SELECT storage_key FROM config_uploads WHERE snapshot_id = ?
                """, (rs, row) -> rs.getString(1), snapshotId, snapshotId);
    }

    public void deleteSnapshot(UUID snapshotId) {
        jdbc.update("DELETE FROM config_snapshots WHERE id = ?", snapshotId);
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

    /** Updates desired state only when the incoming snapshot is a newer revision. */
    public void upsertBindingIfNewer(ConfigBindingRecord binding, long revision) {
        jdbc.update("""
                INSERT INTO config_bindings(id, subject, agent_id, snapshot_id, desired_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (subject, agent_id) DO UPDATE SET snapshot_id = EXCLUDED.snapshot_id,
                    desired_at = EXCLUDED.desired_at
                 WHERE (SELECT version FROM config_snapshots WHERE id = EXCLUDED.snapshot_id)
                     > (SELECT version FROM config_snapshots WHERE id = config_bindings.snapshot_id)
                """, binding.id(), binding.subject(), binding.agentId(), binding.snapshotId(),
                java.sql.Timestamp.from(binding.desiredAt()));
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

    public Optional<ConfigBindingRecord> findBindingForUpdate(UUID bindingId) {
        return jdbc.query("""
                SELECT id, subject, agent_id, snapshot_id, desired_at
                  FROM config_bindings WHERE id = ? FOR UPDATE
                """, (rs, row) -> new ConfigBindingRecord(rs.getObject("id", UUID.class), rs.getString("subject"),
                rs.getObject("agent_id", UUID.class), rs.getObject("snapshot_id", UUID.class),
                instant(rs, "desired_at")), bindingId).stream().findFirst();
    }

    public Optional<ConfigBindingStatus> findBindingStatus(UUID bindingId) {
        return jdbc.query("""
                SELECT b.id AS binding_id, b.subject, b.agent_id, b.snapshot_id AS binding_snapshot_id,
                       b.desired_at, s.id AS snapshot_id, s.subject AS snapshot_subject, s.version,
                       s.manifest::text, s.checksum, s.actor, s.created_at,
                       a.id AS apply_id, a.agent_id AS apply_agent_id, a.snapshot_id AS apply_snapshot_id,
                       a.phase, a.error_message, a.applied_at, a.updated_at,
                       a.observed_version, a.failure_code, a.rollback
                  FROM config_bindings b
                  JOIN config_snapshots s ON s.id = b.snapshot_id
                  LEFT JOIN config_apply_records a
                    ON a.binding_id = b.id AND a.snapshot_id = b.snapshot_id
                 WHERE b.id = ?
                """, (rs, row) -> {
            ConfigBindingRecord binding = new ConfigBindingRecord(rs.getObject("binding_id", UUID.class),
                    rs.getString("subject"), rs.getObject("agent_id", UUID.class),
                    rs.getObject("binding_snapshot_id", UUID.class), instant(rs, "desired_at"));
            ConfigSnapshot snapshot = new ConfigSnapshot(rs.getObject("snapshot_id", UUID.class),
                    rs.getString("snapshot_subject"), rs.getLong("version"),
                    ConfigManifestCanonicalizer.normalize(rs.getString("manifest")), rs.getString("checksum"),
                    rs.getString("actor"), instant(rs, "created_at"));
            UUID applyId = rs.getObject("apply_id", UUID.class);
            ConfigApplyRecord apply = applyId == null ? null : new ConfigApplyRecord(applyId, binding.id(),
                    rs.getObject("apply_agent_id", UUID.class), rs.getObject("apply_snapshot_id", UUID.class),
                    rs.getString("phase"), rs.getString("error_message"), timestampOrNull(rs, "applied_at"),
                    instant(rs, "updated_at"), (Long) rs.getObject("observed_version"),
                    rs.getString("failure_code"), rs.getBoolean("rollback"));
            return new ConfigBindingStatus(binding, snapshot, apply);
        }, bindingId).stream().findFirst();
    }

    public Optional<ConfigSnapshot> findLatestAppliedSnapshotForRollback(UUID bindingId, UUID currentSnapshotId) {
        return jdbc.query("""
                SELECT s.id, s.subject, s.version, s.manifest::text, s.checksum, s.actor, s.created_at
                  FROM config_apply_records a
                  JOIN config_snapshots s ON s.id = a.snapshot_id
                 WHERE a.binding_id = ? AND a.snapshot_id <> ? AND a.phase = 'APPLIED'
                 ORDER BY s.version DESC, s.created_at DESC
                 LIMIT 1
                """, (rs, row) -> new ConfigSnapshot(rs.getObject("id", UUID.class), rs.getString("subject"),
                rs.getLong("version"), ConfigManifestCanonicalizer.normalize(rs.getString("manifest")),
                rs.getString("checksum"), rs.getString("actor"), instant(rs, "created_at")),
                bindingId, currentSnapshotId).stream().findFirst();
    }

    /** Starts a new application attempt while retaining the same binding/snapshot history row. */
    public void markApplyPending(UUID bindingId, UUID agentId, UUID snapshotId, UUID eventId, Instant updatedAt) {
        markApplyPending(bindingId, agentId, snapshotId, eventId, updatedAt, null);
    }

    public void markApplyPending(UUID bindingId, UUID agentId, UUID snapshotId, UUID eventId, Instant updatedAt,
            Long observedVersion) {
        jdbc.update("""
                INSERT INTO config_apply_records
                    (id, binding_id, agent_id, snapshot_id, phase, error_message, applied_at, updated_at,
                     observed_version, failure_code, rollback)
                VALUES (?, ?, ?, ?, 'PENDING', NULL, NULL, ?, ?, NULL, false)
                ON CONFLICT (binding_id, snapshot_id) DO UPDATE SET id = EXCLUDED.id,
                    agent_id = EXCLUDED.agent_id, phase = 'PENDING', error_message = NULL,
                    applied_at = NULL, updated_at = EXCLUDED.updated_at,
                    observed_version = EXCLUDED.observed_version, failure_code = NULL, rollback = false
                """, eventId, bindingId, agentId, snapshotId, java.sql.Timestamp.from(updatedAt), observedVersion);
    }

    public void markRollbackRequested(UUID bindingId, UUID snapshotId) {
        jdbc.update("""
                UPDATE config_apply_records
                   SET rollback = true
                 WHERE binding_id = ? AND snapshot_id = ?
                """, bindingId, snapshotId);
    }

    public Optional<ConfigApplyRecord> findApply(UUID bindingId, UUID snapshotId) {
        return jdbc.query("""
                SELECT id, binding_id, agent_id, snapshot_id, phase, error_message, applied_at, updated_at,
                       observed_version, failure_code, rollback
                  FROM config_apply_records WHERE binding_id = ? AND snapshot_id = ?
                """, (rs, row) -> new ConfigApplyRecord(rs.getObject("id", UUID.class),
                rs.getObject("binding_id", UUID.class), rs.getObject("agent_id", UUID.class),
                rs.getObject("snapshot_id", UUID.class), rs.getString("phase"), rs.getString("error_message"),
                timestampOrNull(rs, "applied_at"), instant(rs, "updated_at"),
                (Long) rs.getObject("observed_version"), rs.getString("failure_code"), rs.getBoolean("rollback")),
                bindingId, snapshotId)
                .stream().findFirst();
    }

    public void recordApply(ConfigApplyRecord apply) {
        jdbc.update("""
                INSERT INTO config_apply_records(id, binding_id, agent_id, snapshot_id, phase,
                    error_message, applied_at, updated_at, observed_version, failure_code, rollback)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (binding_id, snapshot_id) DO UPDATE SET phase = EXCLUDED.phase,
                    error_message = EXCLUDED.error_message, applied_at = EXCLUDED.applied_at,
                    updated_at = EXCLUDED.updated_at, observed_version = EXCLUDED.observed_version,
                    failure_code = EXCLUDED.failure_code, rollback = EXCLUDED.rollback
                """, apply.id(), apply.bindingId(), apply.agentId(), apply.snapshotId(), apply.phase(),
                apply.errorMessage(), apply.appliedAt() == null ? null : java.sql.Timestamp.from(apply.appliedAt()),
                java.sql.Timestamp.from(apply.updatedAt()), apply.observedVersion(), apply.failureCode(),
                apply.rollback());
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
