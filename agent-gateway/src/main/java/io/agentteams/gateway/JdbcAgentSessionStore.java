package io.agentteams.gateway;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL-backed lookup for the hashed session token created by Control Plane. */
public final class JdbcAgentSessionStore implements AgentSessionStore {
    private final JdbcTemplate jdbc;

    public JdbcAgentSessionStore(JdbcTemplate jdbc) { this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc"); }

    @Override
    public Optional<AgentSession> findByTokenSha256(String tokenSha256) {
        return jdbc.query("""
                SELECT agent_id, token_sha256, expires_at, revoked_at
                  FROM agent_sessions WHERE token_sha256 = ?
                """, (rs, row) -> new AgentSession(rs.getObject("agent_id", UUID.class),
                        rs.getString("token_sha256"), rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("revoked_at") != null), tokenSha256).stream().findFirst();
    }
}
