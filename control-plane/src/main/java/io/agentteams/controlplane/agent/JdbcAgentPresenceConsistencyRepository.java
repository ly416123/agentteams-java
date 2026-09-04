package io.agentteams.controlplane.agent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL read of the gateway presence projection against the canonical agent phase. */
public final class JdbcAgentPresenceConsistencyRepository implements AgentPresenceConsistencyRepository {
    private final JdbcTemplate jdbc;

    public JdbcAgentPresenceConsistencyRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<UUID> findStaleReadyAgents(Instant lastSeenBefore, int limit) {
        Objects.requireNonNull(lastSeenBefore, "lastSeenBefore");
        return jdbc.query("""
                SELECT agent.id
                  FROM gateway_agent_state state
                  JOIN agents agent ON agent.id::text = state.agent_id
                 WHERE agent.phase = 'READY'
                   AND (state.presence <> 'ONLINE' OR state.last_seen_at < ?)
                 ORDER BY state.last_seen_at, agent.id
                 LIMIT ?
                """, (rs, row) -> rs.getObject("id", UUID.class), Timestamp.from(lastSeenBefore), limit);
    }

    @Override
    public int markOffline(UUID agentId, Instant at) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(at, "at");
        return jdbc.update("""
                UPDATE agents
                   SET phase = 'OFFLINE', updated_at = ?, version = version + 1
                 WHERE id = ? AND phase = 'READY'
                """, Timestamp.from(at), agentId);
    }
}
