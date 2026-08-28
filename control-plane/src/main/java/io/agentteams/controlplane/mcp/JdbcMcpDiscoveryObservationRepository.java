package io.agentteams.controlplane.mcp;

import io.agentteams.controlplane.persistence.JdbcSupport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed instance snapshot store for MCP discovery observations. */
@Repository
public class JdbcMcpDiscoveryObservationRepository implements McpDiscoveryObservationPort {
    private final JdbcTemplate jdbc;

    public JdbcMcpDiscoveryObservationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void record(McpDiscoveryObservation observation) {
        Objects.requireNonNull(observation, "observation");
        jdbc.update("""
                INSERT INTO mcp_discovery_snapshots
                    (server_id, server_revision, instance_id, tools_digest, healthy,
                     failure_category, observed_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (server_id, server_revision, instance_id) DO UPDATE SET
                    tools_digest = EXCLUDED.tools_digest,
                    healthy = EXCLUDED.healthy,
                    failure_category = EXCLUDED.failure_category,
                    observed_at = EXCLUDED.observed_at,
                    expires_at = EXCLUDED.expires_at
                """, observation.serverId(), observation.serverRevision(), observation.instanceId(),
                observation.toolsDigest(), observation.healthy(), observation.failureCategory(),
                JdbcSupport.timestamp(observation.observedAt()), JdbcSupport.timestamp(observation.expiresAt()));
    }

    @Override
    public List<McpDiscoveryObservation> find(UUID serverId, long serverRevision) {
        Objects.requireNonNull(serverId, "serverId");
        if (serverRevision < 0) {
            throw new IllegalArgumentException("serverRevision must not be negative");
        }
        return jdbc.query("""
                SELECT server_id, server_revision, instance_id, tools_digest, healthy,
                       failure_category, observed_at, expires_at
                  FROM mcp_discovery_snapshots
                 WHERE server_id = ? AND server_revision = ?
                 ORDER BY instance_id
                """, this::map, serverId, serverRevision);
    }

    private McpDiscoveryObservation map(ResultSet rs, int row) throws SQLException {
        return new McpDiscoveryObservation(
                rs.getObject("server_id", UUID.class),
                rs.getLong("server_revision"),
                rs.getString("instance_id"),
                rs.getString("tools_digest"),
                rs.getBoolean("healthy"),
                rs.getString("failure_category"),
                JdbcSupport.instant(rs, "observed_at"),
                JdbcSupport.instant(rs, "expires_at"));
    }
}
