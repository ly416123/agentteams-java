package io.agentteams.controlplane.agentspec;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.sql.Types;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcAgentSpecRepository implements AgentSpecRepository {

    private final JdbcTemplate jdbc;

    public JdbcAgentSpecRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(AgentSpecRecord record) {
        jdbc.update("""
                INSERT INTO agent_specs
                    (id, name, runtime, model_provider, model_name, team_ref, desired_state,
                     lifecycle_status, spec, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, record.id(), record.name(), record.runtime(), record.modelProvider(), record.modelName(),
                record.teamRef(), record.desiredState(), record.lifecycleStatus(), json(record.specJson()),
                timestamp(record.createdAt()), timestamp(record.updatedAt()), record.version());
    }

    @Override
    public void updateLifecycle(AgentSpecRecord record, long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE agent_specs
                   SET lifecycle_status = ?, updated_at = ?, version = ?
                 WHERE id = ? AND version = ?
                """, record.lifecycleStatus(), timestamp(record.updatedAt()), record.version(), record.id(),
                expectedVersion);
        if (updated != 1) {
            throw new AgentSpecVersionConflictException(record.id(), expectedVersion);
        }
    }

    @Override
    public Optional<AgentSpecRecord> findById(UUID id) {
        return jdbc.query(selectSql() + " WHERE id = ?", this::map, id).stream().findFirst();
    }

    @Override
    public List<AgentSpecRecord> findAll() {
        return jdbc.query(selectSql() + " ORDER BY name", this::map);
    }

    @Override
    public Optional<IdempotencyRecord> findIdempotency(String key) {
        return jdbc.query("""
                SELECT idempotency_key, request_hash, spec_id, created_at
                  FROM agent_spec_idempotency WHERE idempotency_key = ?
                """, (rs, row) -> new IdempotencyRecord(rs.getString("idempotency_key"),
                rs.getString("request_hash"), rs.getObject("spec_id", UUID.class),
                rs.getTimestamp("created_at").toInstant()), key).stream().findFirst();
    }

    @Override
    public boolean insertIdempotency(IdempotencyRecord record) {
        return jdbc.update("""
                INSERT INTO agent_spec_idempotency (idempotency_key, request_hash, spec_id, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, record.key(), record.requestHash(), record.specId(), timestamp(record.createdAt())) == 1;
    }

    private static String selectSql() {
        return """
                SELECT id, name, runtime, model_provider, model_name, team_ref, desired_state,
                       lifecycle_status, spec::text, created_at, updated_at, version
                  FROM agent_specs
                """;
    }

    private AgentSpecRecord map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new AgentSpecRecord(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("runtime"), rs.getString("model_provider"), rs.getString("model_name"),
                rs.getString("team_ref"), rs.getString("desired_state"), rs.getString("lifecycle_status"),
                rs.getString("spec"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"));
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static SqlParameterValue json(String value) {
        return new SqlParameterValue(Types.OTHER, value);
    }
}
