package io.agentteams.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.agentteams.application.api.McpConnectivityMode;
import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class McpGatewayRouteServiceTest {
    private static final ExecutionContext CONTEXT = new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "user-1");
    private static final UUID CONNECTION_ID = UUID.randomUUID();

    @Test
    void registersRouteForTheOwnedConnectorAndUpdatesHeartbeat() {
        McpConnectionRepository connections = org.mockito.Mockito.mock(McpConnectionRepository.class);
        InMemoryRouteRepository routes = new InMemoryRouteRepository();
        when(connections.find(CONNECTION_ID, CONTEXT)).thenReturn(Optional.of(connection()));
        McpGatewayRouteService service = new McpGatewayRouteService(connections, routes);

        McpGatewayRoute route = service.register(CONTEXT, CONNECTION_ID, "tenant-1/connector-1", 3,
                "{\"healthy\":true}", Instant.parse("2026-08-31T00:00:00Z"));
        McpGatewayRoute updated = service.heartbeat(CONTEXT, route.id(), 4, "{\"healthy\":true}",
                Instant.parse("2026-08-31T00:01:00Z"));

        assertThat(updated.routeVersion()).isEqualTo(4);
        assertThat(updated.status()).isEqualTo(McpGatewayRoute.Status.ACTIVE);
        assertThat(service.list(CONTEXT)).containsExactly(updated);
    }

    @Test
    void rejectsMissingOrForeignConnectorRoute() {
        McpConnectionRepository connections = org.mockito.Mockito.mock(McpConnectionRepository.class);
        when(connections.find(CONNECTION_ID, CONTEXT)).thenReturn(Optional.of(connection()));
        McpGatewayRouteService service = new McpGatewayRouteService(connections, new InMemoryRouteRepository());

        assertThatThrownBy(() -> service.register(CONTEXT, CONNECTION_ID, "tenant-2/connector-1", 1, "{}", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("connector");
        assertThatThrownBy(() -> service.register(CONTEXT, CONNECTION_ID, "tenant-1/connector-1", -1, "{}", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("version");
    }

    private static McpConnection connection() {
        return new McpConnection(CONNECTION_ID, "internal", McpConnectivityMode.CUSTOMER_CONNECTOR,
                "org-1", "tenant-1", "connector://mcp", "secret://mcp", Set.of("query"), true,
                "tenant-1/connector-1", "key-1", "hash-1", Instant.parse("2026-08-31T00:00:00Z"));
    }

    private static final class InMemoryRouteRepository implements McpGatewayRouteRepository {
        private final java.util.Map<UUID, McpGatewayRoute> routes = new java.util.LinkedHashMap<>();

        @Override
        public McpGatewayRoute upsert(McpGatewayRoute route) { routes.put(route.id(), route); return route; }

        @Override
        public Optional<McpGatewayRoute> find(UUID id, ExecutionContext context) { return Optional.ofNullable(routes.get(id)); }

        @Override
        public List<McpGatewayRoute> find(ExecutionContext context) { return List.copyOf(routes.values()); }
    }
}
