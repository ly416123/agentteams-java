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
