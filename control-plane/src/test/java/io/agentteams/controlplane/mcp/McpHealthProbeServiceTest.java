package io.agentteams.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.security.OutboundPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpHealthProbeServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Mock
    private McpServerService serverService;

    @Mock
    private AuditRecorder auditRecorder;

    @Mock
    private McpTransportConnector connector;

    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void successfulProbeIsUpAndReportsLatencyWithoutSensitiveData() {
        UUID id = UUID.randomUUID();
        when(serverService.get(id)).thenReturn(server(id, true));
        when(connector.transport()).thenReturn(McpTransport.SSE);
        when(connector.supports(McpTransport.SSE)).thenReturn(true);
        when(connector.discoverTools(any(), any())).thenReturn(List.of(new McpToolDescriptor("search", "", "{}")));
        McpHealthProbeResult result = new McpHealthProbeService(discoveryService(), Clock.fixed(NOW, ZoneId.of("UTC")))
                .probe(id, Duration.ofSeconds(1));

        assertThat(result.status()).isEqualTo(McpHealthStatus.HEALTHY);
        assertThat(result.probeStatus()).isEqualTo(McpHealthProbeResult.Status.UP);
        assertThat(result.statusValue()).isEqualTo("UP");
        assertThat(result.category()).isEqualTo(McpHealthProbeCategory.SUCCESS);
        assertThat(result.latencyMillis()).isGreaterThanOrEqualTo(0);
        assertThat(result.detail()).isNull();
    }

    @Test
    void connectorTimeoutIsDownWithStableCategoryAndNoExceptionMessage() {
        UUID id = UUID.randomUUID();
        when(serverService.get(id)).thenReturn(server(id, true));
        when(connector.transport()).thenReturn(McpTransport.SSE);
        when(connector.supports(McpTransport.SSE)).thenReturn(true);
        doThrow(new McpHttpConnectorException(McpHttpFailureCategory.TIMEOUT,
                "endpoint=https://secret.example/mcp payload=secret"))
                .when(connector).discoverTools(any(), any());
        McpHealthProbeResult result = new McpHealthProbeService(discoveryService(), Clock.fixed(NOW, ZoneId.of("UTC")))
                .probe(id, Duration.ofSeconds(1));

        assertThat(result.probeStatus()).isEqualTo(McpHealthProbeResult.Status.DOWN);
        assertThat(result.category()).isEqualTo(McpHealthProbeCategory.TIMEOUT);
        assertThat(result.detail()).isEqualTo("TIMEOUT");
        assertThat(result.detail()).doesNotContain("secret", "example", "payload");
    }

    @Test
    void policyRejectionIsDownWithoutCallingConnector() {
        UUID id = UUID.randomUUID();
        when(serverService.get(id)).thenReturn(server(id, false));
        when(connector.transport()).thenReturn(McpTransport.SSE);
        when(connector.supports(McpTransport.SSE)).thenReturn(true);
        McpHealthProbeResult result = new McpHealthProbeService(discoveryService(), Clock.fixed(NOW, ZoneId.of("UTC")))
                .probe(id, Duration.ofSeconds(1));

        assertThat(result.probeStatus()).isEqualTo(McpHealthProbeResult.Status.DOWN);
        assertThat(result.category()).isEqualTo(McpHealthProbeCategory.POLICY_REJECTED);
    }

    private McpToolExecutionService discoveryService() {
        executor = Executors.newSingleThreadExecutor();
        return new McpToolExecutionService(serverService, new McpRuntimePolicyService(),
                new McpTransportConnectorRegistry(List.of(connector)), auditRecorder,
                Clock.fixed(NOW, ZoneId.of("UTC")), executor,
                new McpToolDiscoveryCache(Clock.fixed(NOW, ZoneId.of("UTC")), Duration.ofMinutes(1), 4));
    }

    private static McpServerRecord server(UUID id, boolean enabled) {
        return new McpServerRecord(id, "weather", McpTransport.SSE, "https://mcp.example.test/sse", "secret/mcp",
                enabled, McpHealthStatus.HEALTHY, NOW, NOW, NOW, 0,
                new OutboundPolicy(Set.of("https"), Set.of("mcp.example.test"), Duration.ofSeconds(5), Set.of()));
    }
}
