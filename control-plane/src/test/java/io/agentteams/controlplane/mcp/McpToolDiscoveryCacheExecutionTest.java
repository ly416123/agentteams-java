package io.agentteams.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
class McpToolDiscoveryCacheExecutionTest {
    private static final Instant START = Instant.parse("2026-08-23T00:00:00Z");

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
    void successfulDiscoveryIsCachedAndHitDoesNotCallConnector() {
        UUID id = UUID.randomUUID();
        MutableClock clock = new MutableClock(START);
        when(serverService.get(id)).thenReturn(server(id));
        configureConnector();
        List<McpToolDescriptor> tools = List.of(new McpToolDescriptor("search", "Search", "{}"));
        when(connector.discoverTools(any(), any())).thenReturn(tools);
        McpToolExecutionService service = service(clock, new McpToolDiscoveryCache(clock, Duration.ofMinutes(1), 4));

        assertThat(service.discoverTools(id, Duration.ofSeconds(1)).value()).isEqualTo(tools);
        assertThat(service.discoverTools(id, Duration.ofSeconds(1)).value()).isEqualTo(tools);

        verify(connector, times(1)).discoverTools(any(), any());
    }

    @Test
    void expiredDiscoveryCallsConnectorAgain() {
        UUID id = UUID.randomUUID();
        MutableClock clock = new MutableClock(START);
        when(serverService.get(id)).thenReturn(server(id));
        configureConnector();
        when(connector.discoverTools(any(), any())).thenReturn(
                List.of(new McpToolDescriptor("first", "", "{}")),
                List.of(new McpToolDescriptor("second", "", "{}")));
        McpToolExecutionService service = service(clock, new McpToolDiscoveryCache(clock, Duration.ofSeconds(5), 4));

        service.discoverTools(id, Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(5));
        McpToolCallResult result = service.discoverTools(id, Duration.ofSeconds(1));

        assertThat(result.value()).isEqualTo(List.of(new McpToolDescriptor("second", "", "{}")));
        verify(connector, times(2)).discoverTools(any(), any());
    }

    @Test
    void serverVersionChangeDoesNotReusePreviousDiscovery() {
        UUID id = UUID.randomUUID();
        MutableClock clock = new MutableClock(START);
        when(serverService.get(id)).thenReturn(server(id, 1), server(id, 2));
        configureConnector();
        when(connector.discoverTools(any(), any())).thenReturn(
                List.of(new McpToolDescriptor("first", "", "{}")),
                List.of(new McpToolDescriptor("second", "", "{}")));
        McpToolExecutionService service = service(clock, new McpToolDiscoveryCache(clock, Duration.ofMinutes(1), 4));

        assertThat(service.discoverTools(id, Duration.ofSeconds(1)).value())
                .isEqualTo(List.of(new McpToolDescriptor("first", "", "{}")));
        assertThat(service.discoverTools(id, Duration.ofSeconds(1)).value())
                .isEqualTo(List.of(new McpToolDescriptor("second", "", "{}")));
        verify(connector, times(2)).discoverTools(any(), any());
    }

    @Test
    void failedDiscoveryDoesNotPopulateCache() {
        UUID id = UUID.randomUUID();
        MutableClock clock = new MutableClock(START);
        when(serverService.get(id)).thenReturn(server(id));
        configureConnector();
        doThrow(new IllegalStateException("endpoint=https://secret.example/mcp payload=secret"))
                .doReturn(List.of(new McpToolDescriptor("search", "", "{}")))
                .when(connector).discoverTools(any(), any());
        McpToolExecutionService service = service(clock, new McpToolDiscoveryCache(clock, Duration.ofMinutes(1), 4));

        McpToolCallResult failed = service.discoverTools(id, Duration.ofSeconds(1));
        McpToolCallResult recovered = service.discoverTools(id, Duration.ofSeconds(1));

        assertThat(failed.outcome()).isEqualTo(McpOperationOutcome.CONNECTOR_ERROR);
        assertThat(recovered.outcome()).isEqualTo(McpOperationOutcome.SUCCESS);
        verify(connector, times(2)).discoverTools(any(), any());
    }

    private void configureConnector() {
        when(connector.transport()).thenReturn(McpTransport.SSE);
        when(connector.supports(McpTransport.SSE)).thenReturn(true);
    }

    private McpToolExecutionService service(Clock clock, McpToolDiscoveryCache cache) {
        executor = Executors.newSingleThreadExecutor();
        return new McpToolExecutionService(serverService, new McpRuntimePolicyService(),
                new McpTransportConnectorRegistry(List.of(connector)), auditRecorder, clock, executor, cache);
    }

    private static McpServerRecord server(UUID id) {
        return server(id, 0);
    }

    private static McpServerRecord server(UUID id, long version) {
        return new McpServerRecord(id, "weather", McpTransport.SSE, "https://mcp.example.test/sse", "secret/mcp",
                true, McpHealthStatus.HEALTHY, START, START, START, version,
                new OutboundPolicy(Set.of("https"), Set.of("mcp.example.test"), Duration.ofSeconds(5), Set.of()));
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() { return ZoneId.of("UTC"); }

        @Override
        public Clock withZone(ZoneId zone) { return this; }

        @Override
        public Instant instant() { return current; }
    }
}
