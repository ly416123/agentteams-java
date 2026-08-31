package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.agentteams.application.api.McpConnectivityMode;
import io.agentteams.controlplane.mcp.McpConnectionService;
import io.agentteams.controlplane.mcp.McpConnectionView;
import io.agentteams.controlplane.mcp.McpGatewayRoute;
import io.agentteams.controlplane.mcp.McpGatewayRouteService;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.ExecutionContextResolver;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

class McpConnectionControllerTest {
    private static final ExecutionContext CONTEXT = new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "user-1");

    @AfterEach
    void clearContext() { PrincipalContext.clear(); }

    @Test
    void listsCredentialFreeConnectionsForTheResolvedTenant() throws Exception {
        McpConnectionService connections = Mockito.mock(McpConnectionService.class);
        McpGatewayRouteService routes = Mockito.mock(McpGatewayRouteService.class);
        ExecutionContextResolver resolver = Mockito.mock(ExecutionContextResolver.class);
        Principal principal = new Principal("user-1", new AuthorizationService.Scope("tenant-1", "project-1", "team-1"), Set.of());
        PrincipalContext.set(principal);
        when(resolver.resolve(principal)).thenReturn(CONTEXT);
        when(connections.list(CONTEXT)).thenReturn(List.of(new McpConnectionView(UUID.randomUUID(), "search",
                McpConnectivityMode.CUSTOMER_CONNECTOR, "org-1", "tenant-1", "ref://endpoint", Set.of("search"),
                true, "tenant-1/connector-1", true, "digest", Instant.parse("2026-08-31T00:00:00Z"))));

        MockMvc mvc = standaloneSetup(new McpConnectionController(connections, routes, resolver)).build();
        mvc.perform(get("/api/v1/mcp/connections")).andExpect(status().isOk());
    }

    @Test
    void createsTenantOwnedConnectionOnlyThroughTheResolvedContext() throws Exception {
        McpConnectionService connections = Mockito.mock(McpConnectionService.class);
        McpGatewayRouteService routes = Mockito.mock(McpGatewayRouteService.class);
        ExecutionContextResolver resolver = Mockito.mock(ExecutionContextResolver.class);
        Principal principal = new Principal("user-1", new AuthorizationService.Scope("tenant-1", "project-1", "team-1"), Set.of());
        PrincipalContext.set(principal);
        when(resolver.resolve(principal)).thenReturn(CONTEXT);
        when(connections.create(eq("key-1"), any(), eq(CONTEXT), any())).thenReturn(
                new McpConnectionView(UUID.randomUUID(), "search", McpConnectivityMode.CUSTOMER_CONNECTOR,
                        "org-1", "tenant-1", "ref://endpoint", Set.of("search"), true,
                        "tenant-1/connector-1", true, "digest", Instant.now()));

        MockMvc mvc = standaloneSetup(new McpConnectionController(connections, routes, resolver)).build();
        mvc.perform(post("/api/v1/mcp/connections").header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content("{\"name\":\"search\",\"mode\":\"CUSTOMER_CONNECTOR\",\"endpointRef\":\"ref://endpoint\",\"credentialRef\":\"secret://mcp\",\"allowedTools\":[\"search\"],\"connectorId\":\"tenant-1/connector-1\"}"))
                .andExpect(status().isCreated());
    }
}
