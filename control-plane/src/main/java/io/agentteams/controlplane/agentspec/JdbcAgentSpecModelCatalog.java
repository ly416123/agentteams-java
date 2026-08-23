package io.agentteams.controlplane.agentspec;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentSpecModelCatalog implements AgentSpecModelCatalog {

    private final JdbcTemplate jdbc;

    public JdbcAgentSpecModelCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProviderReference> findProviderByName(String name) {
        return jdbc.query("""
                SELECT id, enabled, version
                  FROM model_providers
                 WHERE name = ?
                """, (rs, row) -> new ProviderReference(rs.getObject("id", UUID.class),
                rs.getBoolean("enabled"), rs.getLong("version")), name).stream().findFirst();
    }

    @Override
    public Optional<ModelReference> findModelById(UUID providerId, String modelId) {
        return jdbc.query("""
                SELECT enabled, version
                  FROM models
                 WHERE provider_id = ? AND model_id = ?
                """, (rs, row) -> new ModelReference(rs.getBoolean("enabled"), rs.getLong("version")), providerId, modelId)
                .stream().findFirst();
    }
}
