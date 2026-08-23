package io.agentteams.controlplane.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class ModelRepository {

    private final JdbcTemplate jdbc;

    ModelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ModelRecord model) {
        jdbc.update("""
                INSERT INTO models
                    (id, provider_id, name, model_id, capabilities, enabled,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, model.id(), model.providerId(), model.name(), model.modelId(),
                JdbcSupport.json(model.capabilitiesJson()), model.enabled(),
                JdbcSupport.timestamp(model.createdAt()), JdbcSupport.timestamp(model.updatedAt()),
                model.version());
    }

    public Optional<ModelRecord> findById(UUID id) {
        return jdbc.query(selectSql() + " WHERE id = ?", this::map, id).stream().findFirst();
    }

    public List<ModelRecord> findByProviderId(UUID providerId) {
        return jdbc.query(selectSql() + " WHERE provider_id = ? ORDER BY name", this::map, providerId);
    }

    public long countActiveAgentSpecReferences(UUID providerId, String modelId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM agent_specs a
                  JOIN model_providers p ON p.name = a.model_provider
                 WHERE p.id = ? AND a.model_name = ? AND a.lifecycle_status <> 'DISABLED'
                """, Long.class, providerId, modelId);
        return count == null ? 0 : count;
    }

    public ModelRecord updateEnabled(UUID id, boolean enabled, long expectedVersion,
            java.time.Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE models
                   SET enabled = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, enabled, JdbcSupport.timestamp(updatedAt), id, expectedVersion);
        if (updated == 0) {
            long actual = jdbc.query("SELECT version FROM models WHERE id = ?",
                    (rs, row) -> rs.getLong(1), id).stream().findFirst().orElse(-1L);
            throw new OptimisticLockFailure("model", id, expectedVersion, actual);
        }
        return findById(id).orElseThrow();
    }

    public void delete(UUID id, long expectedVersion) {
        int deleted = jdbc.update("DELETE FROM models WHERE id = ? AND version = ?", id, expectedVersion);
        if (deleted == 0) {
            long actual = jdbc.query("SELECT version FROM models WHERE id = ?",
                    (rs, row) -> rs.getLong(1), id).stream().findFirst().orElse(-1L);
            throw new OptimisticLockFailure("model", id, expectedVersion, actual);
        }
    }

    private static String selectSql() {
        return """
                SELECT id, provider_id, name, model_id, capabilities::text,
                       enabled, created_at, updated_at, version
                  FROM models
                """;
    }

    private ModelRecord map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ModelRecord(rs.getObject("id", UUID.class), rs.getObject("provider_id", UUID.class),
                rs.getString("name"), rs.getString("model_id"), rs.getString("capabilities"),
                rs.getBoolean("enabled"), JdbcSupport.instant(rs, "created_at"),
                JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
