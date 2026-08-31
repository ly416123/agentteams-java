package io.agentteams.controlplane.mcp;

import io.agentteams.controlplane.persistence.JdbcSupport;
import io.agentteams.controlplane.security.ExecutionContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;

/** PostgreSQL route health projection joined to the owning tenant connection. */
@Repository
public class JdbcMcpGatewayRouteRepository implements McpGatewayRouteRepository {
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcMcpGatewayRouteRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcMcpGatewayRouteRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public McpGatewayRoute upsert(McpGatewayRoute route) {
        jdbc.update("""
                INSERT INTO mcp_connector_routes
                    (id, connection_id, connector_id, route_version, status, last_heartbeat_at,
                     health_summary, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (connector_id) DO UPDATE SET
                    connection_id = EXCLUDED.connection_id, route_version = EXCLUDED.route_version,
                    status = EXCLUDED.status, last_heartbeat_at = EXCLUDED.last_heartbeat_at,
                    health_summary = EXCLUDED.health_summary, updated_at = EXCLUDED.updated_at
                """, route.id(), route.connectionId(), route.connectorId(), route.routeVersion(), route.status().name(),
                nullableTimestamp(route.lastHeartbeatAt()), json(route.healthSummaryJson()),
                JdbcSupport.timestamp(route.createdAt()), JdbcSupport.timestamp(route.updatedAt()));
        return findRaw(route.connectorId()).orElseThrow(() -> new IllegalStateException("route was not persisted"));
    }

    @Override
    public Optional<McpGatewayRoute> find(UUID id, ExecutionContext context) {
        Objects.requireNonNull(id, "id");
        if (context == null) return Optional.empty();
        return jdbc.query(select() + " WHERE route.id = ? AND connection.organization_id = ?"
                        + " AND connection.tenant_id = ?", this::map, id,
                context.organizationId(), context.tenantId()).stream().findFirst();
    }

    @Override
    public List<McpGatewayRoute> find(ExecutionContext context) {
        if (context == null) return List.of();
        return jdbc.query(select() + " WHERE connection.organization_id = ? AND connection.tenant_id = ?"
                        + " ORDER BY route.connector_id", this::map, context.organizationId(), context.tenantId());
    }

    private Optional<McpGatewayRoute> findRaw(String connectorId) {
        return jdbc.query(select() + " WHERE route.connector_id = ?", this::map, connectorId).stream().findFirst();
    }

    private McpGatewayRoute map(ResultSet rs, int row) throws SQLException {
        return new McpGatewayRoute(rs.getObject("id", UUID.class), rs.getObject("connection_id", UUID.class),
                rs.getString("connector_id"), rs.getLong("route_version"),
                McpGatewayRoute.Status.valueOf(rs.getString("status")), nullableInstant(rs, "last_heartbeat_at"),
                rs.getString("health_summary"), JdbcSupport.instant(rs, "created_at"),
                JdbcSupport.instant(rs, "updated_at"));
    }

    private static String select() {
        return """
                SELECT route.id, route.connection_id, route.connector_id, route.route_version, route.status,
                       route.last_heartbeat_at, route.health_summary::text, route.created_at, route.updated_at
                  FROM mcp_connector_routes route
                  JOIN mcp_connections connection ON connection.id = route.connection_id
                """;
    }

    private static java.sql.Timestamp nullableTimestamp(java.time.Instant value) {
        return value == null ? null : JdbcSupport.timestamp(value);
    }

    private static java.time.Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static SqlParameterValue json(String value) {
        return new SqlParameterValue(Types.OTHER, value);
    }
}
