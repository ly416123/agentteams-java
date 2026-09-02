package io.agentteams.controlplane.mcp;

import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns connector route registration and health heartbeats for tenant-owned MCP connections. */
@Service
public class McpGatewayRouteService {
    private final McpConnectionRepository connections;
    private final McpGatewayRouteRepository routes;

    public McpGatewayRouteService(McpConnectionRepository connections, McpGatewayRouteRepository routes) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.routes = Objects.requireNonNull(routes, "routes");
    }

    @Transactional
    public McpGatewayRoute register(ExecutionContext context, UUID connectionId, String connectorId,
            long routeVersion, String healthSummaryJson, Instant now) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(now, "now");
        if (routeVersion < 0) throw new IllegalArgumentException("route version must not be negative");
        McpConnection connection = connections.find(connectionId, context)
                .orElseThrow(() -> new IllegalArgumentException("MCP connection is not visible in this context"));
        if (connection.mode() != io.agentteams.application.api.McpConnectivityMode.CUSTOMER_CONNECTOR) {
            throw new IllegalArgumentException("route requires a CUSTOMER_CONNECTOR connection");
        }
        if (!connection.connectorId().equals(connectorId)) {
            throw new IllegalArgumentException("connector does not match the MCP connection");
        }
        McpGatewayRoute route = new McpGatewayRoute(UUID.randomUUID(), connectionId, connectorId, routeVersion,
                McpGatewayRoute.Status.ACTIVE, now, healthSummaryJson, now, now);
        return routes.upsert(route);
    }

    @Transactional
    public McpGatewayRoute heartbeat(ExecutionContext context, UUID routeId, long routeVersion,
            String healthSummaryJson, Instant now) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(now, "now");
        if (routeVersion < 0) throw new IllegalArgumentException("route version must not be negative");
        McpGatewayRoute current = routes.find(routeId, context)
                .orElseThrow(() -> new IllegalArgumentException("MCP route is not visible in this context"));
        return routes.upsert(new McpGatewayRoute(current.id(), current.connectionId(), current.connectorId(),
                routeVersion, McpGatewayRoute.Status.ACTIVE, now, healthSummaryJson,
                current.createdAt(), now));
    }

    public Optional<McpGatewayRoute> get(ExecutionContext context, UUID routeId) {
        return routes.find(Objects.requireNonNull(routeId, "routeId"), context);
    }

    public List<McpGatewayRoute> list(ExecutionContext context) {
        return List.copyOf(routes.find(context));
    }
}
