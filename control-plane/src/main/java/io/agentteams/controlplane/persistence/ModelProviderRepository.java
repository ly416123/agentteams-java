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
