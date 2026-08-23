package io.agentteams.controlplane.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class ModelProviderRepository {

    private final JdbcTemplate jdbc;

    ModelProviderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ModelProviderRecord provider) {
        jdbc.update("""
                INSERT INTO model_providers
                    (id, name, provider_type, endpoint, credential_ref, settings, enabled,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, provider.id(), provider.name(), provider.providerType(), provider.endpoint(),
                provider.credentialRef(), JdbcSupport.json(provider.settingsJson()), provider.enabled(),
                JdbcSupport.timestamp(provider.createdAt()), JdbcSupport.timestamp(provider.updatedAt()),
                provider.version());
    }

    public Optional<ModelProviderRecord> findById(UUID id) {
        return jdbc.query(selectSql() + " WHERE id = ?", this::map, id).stream().findFirst();
    }

    public List<ModelProviderRecord> findAll() {
        return jdbc.query(selectSql() + " ORDER BY name", this::map);
    }

    public long countModels(UUID providerId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM models WHERE provider_id = ?",
                Long.class, providerId);
        return count == null ? 0 : count;
    }

    public long countActiveAgentSpecReferences(String providerName) {
        Long count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM agent_specs
                 WHERE model_provider = ? AND lifecycle_status <> 'DISABLED'
                """, Long.class, providerName);
        return count == null ? 0 : count;
    }

    public ModelProviderRecord updateEnabled(UUID id, boolean enabled, long expectedVersion,
            java.time.Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE model_providers
                   SET enabled = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, enabled, JdbcSupport.timestamp(updatedAt), id, expectedVersion);
        if (updated == 0) {
            long actual = jdbc.query("SELECT version FROM model_providers WHERE id = ?",
                    (rs, row) -> rs.getLong(1), id).stream().findFirst().orElse(-1L);
            throw new OptimisticLockFailure("model_provider", id, expectedVersion, actual);
        }
        return findById(id).orElseThrow();
    }

    public void delete(UUID id, long expectedVersion) {
        int deleted = jdbc.update("DELETE FROM model_providers WHERE id = ? AND version = ?",
                id, expectedVersion);
        if (deleted == 0) {
            long actual = jdbc.query("SELECT version FROM model_providers WHERE id = ?",
                    (rs, row) -> rs.getLong(1), id).stream().findFirst().orElse(-1L);
            throw new OptimisticLockFailure("model_provider", id, expectedVersion, actual);
        }
    }

    private static String selectSql() {
        return """
                SELECT id, name, provider_type, endpoint, credential_ref, settings::text,
                       enabled, created_at, updated_at, version
                  FROM model_providers
                """;
    }

    private ModelProviderRecord map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ModelProviderRecord(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("provider_type"), rs.getString("endpoint"), rs.getString("credential_ref"),
                rs.getString("settings"), rs.getBoolean("enabled"),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"),
                rs.getLong("version"));
    }
}
