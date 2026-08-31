package io.agentteams.controlplane.api;

import io.agentteams.application.api.McpConnectivityMode;
import io.agentteams.controlplane.mcp.McpConnectionService;
import io.agentteams.controlplane.mcp.McpConnectionView;
import io.agentteams.controlplane.mcp.McpGatewayRoute;
import io.agentteams.controlplane.mcp.McpGatewayRouteService;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.ExecutionContextResolver;
import io.agentteams.controlplane.security.PrincipalContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP management boundary for public MCP catalog entries and tenant-owned connections. */
@RestController
@RequestMapping("/api/v1/mcp")
public final class McpConnectionController {
    private final McpConnectionService connections;
    private final McpGatewayRouteService routes;
    private final ExecutionContextResolver contextResolver;

    public McpConnectionController(McpConnectionService connections, McpGatewayRouteService routes,
            ExecutionContextResolver contextResolver) {
        this.connections = connections;
        this.routes = routes;
        this.contextResolver = contextResolver;
    }

    @GetMapping("/connections")
    public List<McpConnectionView> listConnections() {
        return connections.list(optionalContext());
    }

    @GetMapping("/connections/{connectionId}")
    public ResponseEntity<McpConnectionView> getConnection(@PathVariable UUID connectionId) {
        return connections.get(connectionId, optionalContext()).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/connections")
    public ResponseEntity<McpConnectionView> createConnection(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateConnectionRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (request == null) throw new IllegalArgumentException("request body is required");
        McpConnectionView created = connections.create(idempotencyKey,
                new McpConnectionService.CreateInput(request.name(), request.mode(), request.organizationId(),
                        request.tenantId(), request.endpointRef(), request.credentialRef(), request.allowedTools(),
                        request.enabled(), request.connectorId()), optionalContext(), Instant.now());
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping("/routes")
    public List<McpGatewayRoute> listRoutes() {
        return routes.list(requiredContext());
    }

    @PostMapping("/connections/{connectionId}/route")
    public McpGatewayRoute registerRoute(@PathVariable UUID connectionId, @RequestBody RouteRequest request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
        return routes.register(requiredContext(), connectionId, request.connectorId(), request.routeVersion(),
                request.healthSummaryJson(), Instant.now());
    }

    @PostMapping("/routes/{routeId}/heartbeat")
    public McpGatewayRoute heartbeat(@PathVariable UUID routeId, @RequestBody RouteRequest request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
        return routes.heartbeat(requiredContext(), routeId, request.routeVersion(), request.healthSummaryJson(),
                Instant.now());
    }

    private ExecutionContext optionalContext() {
        return PrincipalContext.current().map(contextResolver::resolve).orElse(null);
    }

    private ExecutionContext requiredContext() {
        ExecutionContext context = optionalContext();
        if (context == null) throw new AuthorizationException("authentication required");
        return context;
    }

    public record CreateConnectionRequest(String name, McpConnectivityMode mode, String organizationId,
            String tenantId, String endpointRef, String credentialRef, Set<String> allowedTools, Boolean enabled,
            String connectorId) { }

    public record RouteRequest(String connectorId, long routeVersion, String healthSummaryJson) { }
}
