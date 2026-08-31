package io.agentteams.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.application.api.McpConnectivityMode;
import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Instant;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class McpConnectionServiceTest {

    private static final ExecutionContext TENANT_A = new ExecutionContext("org-a", "tenant-a", "project-a", "team-a", "user-a");
    private static final ExecutionContext TENANT_B = new ExecutionContext("org-b", "tenant-b", "project-b", "team-b", "user-b");

    @Test
    void publicConnectionIsVisibleWithoutTenantAndNeverReturnsCredential() {
        McpConnectionService service = new McpConnectionService();
        McpConnectionView view = service.create("public-1", new McpConnectionService.CreateInput(
                "search", McpConnectivityMode.PLATFORM_PUBLIC, null, null,
                "https://mcp.example.test", "secret/public", Set.of("search"), true), null,
                Instant.parse("2026-08-31T00:00:00Z"));

        assertThat(service.list(null)).containsExactly(view);
        assertThat(view.credentialConfigured()).isTrue();
        assertThat(view.credentialRef()).isNull();
        assertThat(view.credentialRefDigest()).hasSize(64).doesNotContain("secret");
    }

    @Test
    void privateConnectionRequiresTenantAndCannotCrossTenant() {
        McpConnectionService service = new McpConnectionService();
        McpConnectionView view = service.create("private-1", new McpConnectionService.CreateInput(
                "internal", McpConnectivityMode.PRIVATE_DEPLOYMENT, "org-a", "tenant-a",
                "secret://mcp/internal", "secret/private", Set.of("query"), true), TENANT_A,
                Instant.parse("2026-08-31T00:00:00Z"));

        assertThat(service.get(view.id(), TENANT_A)).contains(view);
        assertThat(service.get(view.id(), TENANT_B)).isEmpty();
        assertThat(service.list(TENANT_B)).isEmpty();
        assertThatThrownBy(() -> service.create("missing-context", new McpConnectionService.CreateInput(
                "internal", McpConnectivityMode.PRIVATE_DEPLOYMENT, "org-a", "tenant-a",
                "secret://mcp/internal", "secret/private", Set.of(), true), null,
                Instant.parse("2026-08-31T00:00:00Z"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void connectorRouteRequiresConnectorIdAndIdempotencyIsRequestBound() {
        McpConnectionService service = new McpConnectionService();
        assertThatThrownBy(() -> service.create("connector-missing", new McpConnectionService.CreateInput(
                "private", McpConnectivityMode.CUSTOMER_CONNECTOR, "org-a", "tenant-a",
                "connector://mcp", "secret/connector", Set.of(), true), TENANT_A,
                Instant.parse("2026-08-31T00:00:00Z"))).isInstanceOf(IllegalArgumentException.class);

        McpConnectionService.CreateInput input = new McpConnectionService.CreateInput(
                "private", McpConnectivityMode.CUSTOMER_CONNECTOR, "org-a", "tenant-a",
                "connector://mcp", "secret/connector", Set.of(), true, "tenant-a/connector-1");
        McpConnectionView first = service.create("same-key", input, TENANT_A, Instant.now());
        assertThat(service.create("same-key", input, TENANT_A, Instant.now())).isEqualTo(first);
        assertThatThrownBy(() -> service.create("same-key", new McpConnectionService.CreateInput(
                "other", McpConnectivityMode.CUSTOMER_CONNECTOR, "org-a", "tenant-a",
                "connector://mcp", "secret/connector", Set.of(), true, "tenant-a/connector-1"), TENANT_A, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotency");
    }

    @Test
    void snapshotsAllowedToolsAndNormalizesNullEnabledForIdempotency() {
        McpConnectionService service = new McpConnectionService();
        Set<String> tools = new HashSet<>(Set.of("search"));
        McpConnectionView first = service.create("stable-key", new McpConnectionService.CreateInput(
                "search", McpConnectivityMode.PLATFORM_PUBLIC, null, null, "https://mcp.example.test",
                null, tools, null), null, Instant.now());
        tools.add("write");

        assertThat(first.allowedTools()).containsExactly("search");
        assertThat(service.create("stable-key", new McpConnectionService.CreateInput(
                "search", McpConnectivityMode.PLATFORM_PUBLIC, null, null, "https://mcp.example.test",
                null, Set.of("search"), true), null, Instant.now())).isEqualTo(first);
    }

    @Test
    void rejectsConnectorIdOwnedByAnotherTenant() {
        McpConnectionService service = new McpConnectionService();

        assertThatThrownBy(() -> service.create("cross-tenant", new McpConnectionService.CreateInput(
                "private", McpConnectivityMode.CUSTOMER_CONNECTOR, "org-a", "tenant-a",
                "connector://mcp", null, Set.of(), true, "tenant-b/connector-1"), TENANT_A, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owned by");
    }
}
