package io.agentteams.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.application.api.McpConnectivityMode;
import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcMcpConnectionRepositoryTest {
    private static final ExecutionContext CONTEXT = new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "user-1");

    @Test
    void insertUsesAnIdempotentOrganizationScopedRecord() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        McpConnection connection = connection(McpConnectivityMode.PRIVATE_DEPLOYMENT);

        boolean inserted = new JdbcMcpConnectionRepository(jdbc).insert(connection);

        assertThat(inserted).isTrue();
        verify(jdbc).update(org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("ON CONFLICT (idempotency_key)")),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void findFiltersPublicAndTenantOwnedConnectionsAtTheDatabaseBoundary() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        McpConnection expected = connection(McpConnectivityMode.PLATFORM_PUBLIC);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<McpConnection>>any(),
                eq(CONTEXT.organizationId()), eq(CONTEXT.tenantId()))).thenReturn(List.of(expected));

        assertThat(new JdbcMcpConnectionRepository(jdbc).find(CONTEXT)).containsExactly(expected);
        verify(jdbc).query(org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("connectivity_mode = 'PLATFORM_PUBLIC'")
                        && sql.contains("organization_id = ?") && sql.contains("tenant_id = ?")),
                org.mockito.ArgumentMatchers.<RowMapper<McpConnection>>any(), eq("org-1"), eq("tenant-1"));
    }

    @Test
    void idempotencyLookupIsAvailableForTheServiceAdapter() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<McpConnection>>any(), eq("key-1")))
                .thenReturn(List.of(connection(McpConnectivityMode.PLATFORM_PUBLIC)));

        assertThat(new JdbcMcpConnectionRepository(jdbc).findByIdempotencyKey("key-1")).isPresent();
    }

    private static McpConnection connection(McpConnectivityMode mode) {
        boolean publicMode = mode == McpConnectivityMode.PLATFORM_PUBLIC;
        return new McpConnection(UUID.randomUUID(), "search", mode, publicMode ? null : "org-1",
                publicMode ? null : "tenant-1", "https://mcp.example.test", "secret://mcp/search",
                Set.of("search"), true, publicMode ? null : "tenant-1/connector-1", "key-1", "hash-1",
                Instant.parse("2026-08-31T00:00:00Z"));
    }
}
