package io.agentteams.controlplane.config;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
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

    public void upsertBinding(ConfigBindingRecord binding) {
        jdbc.update("""
                INSERT INTO config_bindings(id, subject, agent_id, snapshot_id, desired_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (subject, agent_id) DO UPDATE SET snapshot_id = EXCLUDED.snapshot_id,
                    desired_at = EXCLUDED.desired_at
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

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }
}
