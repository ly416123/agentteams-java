package io.agentteams.controlplane.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.McpConnectivityMode;
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

/** PostgreSQL-backed tenant-aware MCP connection store. */
@Repository
public class JdbcMcpConnectionRepository implements McpConnectionRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcMcpConnectionRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcMcpConnectionRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean insert(McpConnection connection) {
        Objects.requireNonNull(connection, "connection");
        Object[] arguments = {
                connection.id(), connection.name(), connection.mode().name(), connection.organizationId(),
                connection.tenantId(), connection.endpointRef(), connection.credentialRef(), json(connection.allowedTools()),
                connection.enabled(), connection.connectorId(), connection.idempotencyKey(), connection.requestHash(),
                JdbcSupport.timestamp(connection.createdAt()), JdbcSupport.timestamp(connection.createdAt())
        };
        return jdbc.update("""
                INSERT INTO mcp_connections
                    (id, name, connectivity_mode, organization_id, tenant_id, endpoint_ref, credential_ref,
                     allowed_tools, enabled, connector_id, idempotency_key, request_hash, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, arguments) == 1;
    }

    @Override
    public Optional<McpConnection> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query(select() + " WHERE idempotency_key = ?", this::map, idempotencyKey).stream().findFirst();
    }

    @Override
    public Optional<McpConnection> find(UUID id, ExecutionContext context) {
        Objects.requireNonNull(id, "id");
        if (context == null) {
            return jdbc.query(select() + " WHERE id = ? AND connectivity_mode = 'PLATFORM_PUBLIC'", this::map, id)
                    .stream().findFirst();
        }
        return jdbc.query(select() + " WHERE id = ? AND (connectivity_mode = 'PLATFORM_PUBLIC'"
                        + " OR (organization_id = ? AND tenant_id = ?))", this::map, id,
                context.organizationId(), context.tenantId()).stream().findFirst();
    }

    @Override
    public List<McpConnection> find(ExecutionContext context) {
        if (context == null) {
            return jdbc.query(select() + " WHERE connectivity_mode = 'PLATFORM_PUBLIC' ORDER BY name, id", this::map);
        }
        return jdbc.query(select() + " WHERE connectivity_mode = 'PLATFORM_PUBLIC'"
                        + " OR (organization_id = ? AND tenant_id = ?) ORDER BY name, id", this::map,
                context.organizationId(), context.tenantId());
    }

    private McpConnection map(ResultSet rs, int row) throws SQLException {
        return new McpConnection(rs.getObject("id", UUID.class), rs.getString("name"),
                McpConnectivityMode.valueOf(rs.getString("connectivity_mode")), rs.getString("organization_id"),
                rs.getString("tenant_id"), rs.getString("endpoint_ref"), rs.getString("credential_ref"),
                tools(rs.getString("allowed_tools")), rs.getBoolean("enabled"), rs.getString("connector_id"),
                rs.getString("idempotency_key"), rs.getString("request_hash"), JdbcSupport.instant(rs, "created_at"));
    }

    private static java.util.Set<String> tools(String value) {
        try {
            return java.util.Set.copyOf(JSON.readValue(value, new TypeReference<List<String>>() { }));
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("MCP allowed tools JSON is invalid", error);
        }
    }

    private static SqlParameterValue json(java.util.Set<String> values) {
        try {
            return new SqlParameterValue(Types.OTHER, JSON.writeValueAsString(values.stream().sorted().toList()));
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("MCP allowed tools cannot be serialized", error);
        }
    }

    private static String select() {
        return """
                SELECT id, name, connectivity_mode, organization_id, tenant_id, endpoint_ref, credential_ref,
                       allowed_tools::text, enabled, connector_id, idempotency_key, request_hash, created_at
                  FROM mcp_connections
                """;
    }
}
