package io.agentteams.controlplane.persistence;

import io.agentteams.domain.agent.AgentPhase;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class AgentRepository {

    private final JdbcTemplate jdbc;

    AgentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AgentRecord agent) {
        jdbc.update("""
                INSERT INTO agents
                    (id, name, phase, runtime, capabilities, metadata, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, agent.id(), agent.name(), agent.phase().name(), agent.runtime(),
                JdbcSupport.json(agent.capabilitiesJson()), JdbcSupport.json(agent.metadataJson()),
                JdbcSupport.timestamp(agent.createdAt()), JdbcSupport.timestamp(agent.updatedAt()), agent.version());
    }

    public Optional<AgentRecord> findById(UUID id) {
        return jdbc.query("""
                SELECT id, name, phase, runtime, capabilities::text, metadata::text,
                       created_at, updated_at, version
                  FROM agents WHERE id = ?
                """, this::map, id).stream().findFirst();
    }

    public Optional<AgentRecord> findByIdForUpdate(UUID id) {
        return jdbc.query("""
                SELECT id, name, phase, runtime, capabilities::text, metadata::text,
                       created_at, updated_at, version
                  FROM agents WHERE id = ? FOR UPDATE
                """, this::map, id).stream().findFirst();
    }

    public Optional<AgentRecord> findReadyMatching(String taskSpecJson) {
        return jdbc.query("""
                SELECT id, name, phase, runtime, capabilities::text, metadata::text,
                       created_at, updated_at, version
                  FROM agents
                 WHERE phase = 'READY'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM jsonb_array_elements_text(
                              CASE
                                  WHEN jsonb_typeof(CAST(? AS jsonb)->'requiredCapabilities') = 'array'
                                  THEN CAST(? AS jsonb)->'requiredCapabilities'
                                  ELSE '[]'::jsonb
                              END) AS required(capability)
                        WHERE NOT jsonb_exists(agents.capabilities, required.capability)
                   )
                 ORDER BY id
                 LIMIT 1
                 FOR UPDATE SKIP LOCKED
                """, this::map, JdbcSupport.json(taskSpecJson), JdbcSupport.json(taskSpecJson))
                .stream().findFirst();
    }

    public Optional<AgentRecord> findReadyMatchingForTeam(String taskSpecJson, UUID teamId) {
        return jdbc.query("""
                SELECT agents.id, agents.name, agents.phase, agents.runtime,
                       agents.capabilities::text, agents.metadata::text,
                       agents.created_at, agents.updated_at, agents.version
                  FROM agents
                  JOIN team_memberships membership ON membership.agent_id = agents.id
                 WHERE membership.team_id = ? AND membership.status = 'ACTIVE'
                   AND agents.phase = 'READY'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM jsonb_array_elements_text(
                              CASE
                                  WHEN jsonb_typeof(CAST(? AS jsonb)->'requiredCapabilities') = 'array'
                                  THEN CAST(? AS jsonb)->'requiredCapabilities'
                                  ELSE '[]'::jsonb
                              END) AS required(capability)
                        WHERE NOT jsonb_exists(agents.capabilities, required.capability)
                   )
                 ORDER BY agents.id
                 LIMIT 1
                 FOR UPDATE OF agents SKIP LOCKED
                """, this::map, teamId, JdbcSupport.json(taskSpecJson), JdbcSupport.json(taskSpecJson))
                .stream().findFirst();
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM agents", Long.class);
        return count == null ? 0 : count;
    }

    public AgentRecord updatePhase(UUID id, AgentPhase phase, long expectedVersion, Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE agents
                   SET phase = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, phase.name(), JdbcSupport.timestamp(updatedAt), id, expectedVersion);
        if (updated == 0) {
            throw new OptimisticLockFailure("agent", id, expectedVersion, actualVersion(id));
        }
        return findById(id).orElseThrow();
    }

    private long actualVersion(UUID id) {
        return jdbc.query("SELECT version FROM agents WHERE id = ?", (rs, row) -> rs.getLong(1), id)
                .stream().findFirst().orElse(-1L);
    }

    private AgentRecord map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new AgentRecord(rs.getObject("id", UUID.class), rs.getString("name"),
                AgentPhase.valueOf(rs.getString("phase")), rs.getString("runtime"),
                rs.getString("capabilities"), rs.getString("metadata"),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"),
                rs.getLong("version"));
    }
}
