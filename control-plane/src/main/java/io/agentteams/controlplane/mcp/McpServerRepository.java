package io.agentteams.controlplane.mcp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public final class McpServerRepository {

    private final JdbcTemplate jdbc;

    public McpServerRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public int insertIdempotency(String key, String requestHash, Instant createdAt) {
        return jdbc.update("""
                INSERT INTO mcp_server_idempotency (idempotency_key, request_hash, server_id, created_at)
                VALUES (?, ?, NULL, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, key, requestHash, timestamp(createdAt));
    }

    public Optional<McpIdempotencyRecord> findIdempotency(String key) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT idempotency_key, request_hash, server_id, created_at
                      FROM mcp_server_idempotency
                     WHERE idempotency_key = ?
                    """, (rs, row) -> new McpIdempotencyRecord(rs.getString("idempotency_key"),
                    rs.getString("request_hash"), rs.getObject("server_id", UUID.class),
                    timestamp(rs, "created_at")), key));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public void bindIdempotency(String key, UUID serverId) {
        jdbc.update("UPDATE mcp_server_idempotency SET server_id = ? WHERE idempotency_key = ?",
                serverId, key);
    }

    public void insert(McpServerRecord server) {
        jdbc.update("""
                INSERT INTO mcp_servers
                    (id, name, transport, endpoint, credential_ref, enabled, health_status,
                     last_checked_at, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, server.id(), server.name(), server.transport().name(), server.endpoint(),
                server.credentialRef(), server.enabled(), server.healthStatus().name(),
                timestamp(server.lastCheckedAt()), timestamp(server.createdAt()),
                timestamp(server.updatedAt()), server.version());
    }

    public List<McpServerRecord> findAll() {
        return jdbc.query(selectSql() + " ORDER BY name", this::map);
    }

    public Optional<McpServerRecord> findById(UUID id) {
        return jdbc.query(selectSql() + " WHERE id = ?", this::map, id).stream().findFirst();
    }

    public int update(McpServerRecord server, long expectedVersion) {
        return jdbc.update("""
                UPDATE mcp_servers
                   SET name = ?, transport = ?, endpoint = ?, credential_ref = ?, enabled = ?,
                       health_status = ?, last_checked_at = ?, updated_at = ?, version = ?
                 WHERE id = ? AND version = ?
                """, server.name(), server.transport().name(), server.endpoint(), server.credentialRef(),
                server.enabled(), server.healthStatus().name(), timestamp(server.lastCheckedAt()),
                timestamp(server.updatedAt()), server.version(), server.id(), expectedVersion);
    }

    public int updateHealth(UUID id, McpHealthStatus status, Instant lastCheckedAt, Instant updatedAt,
            long expectedVersion) {
        return jdbc.update("""
                UPDATE mcp_servers
                   SET health_status = ?, last_checked_at = ?, updated_at = ?, version = ?
                 WHERE id = ? AND version = ?
                """, status.name(), timestamp(lastCheckedAt), timestamp(updatedAt), expectedVersion + 1,
                id, expectedVersion);
    }

    public int delete(UUID id) {
        return jdbc.update("DELETE FROM mcp_servers WHERE id = ?", id);
    }

    private static String selectSql() {
        return """
                SELECT id, name, transport, endpoint, credential_ref, enabled, health_status,
                       last_checked_at, created_at, updated_at, version
                  FROM mcp_servers
                """;
    }

    private McpServerRecord map(ResultSet rs, int row) throws SQLException {
        return new McpServerRecord(rs.getObject("id", UUID.class), rs.getString("name"),
                McpTransport.valueOf(rs.getString("transport")), rs.getString("endpoint"),
                rs.getString("credential_ref"), rs.getBoolean("enabled"),
                McpHealthStatus.valueOf(rs.getString("health_status")),
                timestamp(rs, "last_checked_at"), timestamp(rs, "created_at"),
                timestamp(rs, "updated_at"), rs.getLong("version"));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record McpIdempotencyRecord(String key, String requestHash, UUID serverId, Instant createdAt) {
    }
}
