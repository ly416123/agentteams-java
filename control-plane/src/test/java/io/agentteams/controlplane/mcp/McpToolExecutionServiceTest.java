package io.agentteams.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.security.OutboundPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpToolExecutionServiceTest {
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
    void policyDenialIsAuditedAndConnectorIsNotCalled() {
        UUID id = UUID.randomUUID();
        McpServerRecord server = server(id, false, Set.of("search"));
        when(serverService.get(id)).thenReturn(server);
        McpToolExecutionService service = service(List.of(connector));

        McpToolCallResult result = service.callTool(id,
                new McpToolInvocation("search", Map.of(), Duration.ofSeconds(1)));

        assertThat(result.outcome()).isEqualTo(McpOperationOutcome.DENIED);
        verify(connector, never()).callTool(any(), any(), any(), any());
        assertAudit("MCP_TOOL_CALL", "DENIED", "SERVER_DISABLED");
    }

    @Test
    void missingConnectorIsClassifiedAsUnsupported() {
        UUID id = UUID.randomUUID();
        McpServerRecord server = server(id, true, Set.of("search"));
        when(serverService.get(id)).thenReturn(server);
        McpToolExecutionService service = service(new McpTransportConnectorRegistry());

        McpToolCallResult result = service.callTool(id,
                new McpToolInvocation("search", Map.of(), Duration.ofSeconds(1)));

        assertThat(result.outcome()).isEqualTo(McpOperationOutcome.UNSUPPORTED);
        assertThat(result.classification()).isEqualTo("TRANSPORT_UNSUPPORTED");
        assertAudit("MCP_TOOL_CALL", "UNSUPPORTED", "TRANSPORT_UNSUPPORTED");
    }

    @Test
    void successfulCallAuditsOnlySafeMetadataAndDoesNotExposeCredentialRef() {
        UUID id = UUID.randomUUID();
        McpServerRecord server = server(id, true, Set.of("search"));
        when(serverService.get(id)).thenReturn(server);
        when(connector.supports(McpTransport.SSE)).thenReturn(true);
        when(connector.callTool(any(), eq("search"), eq(Map.of("q", "weather")), any()))
                .thenReturn(Map.of("answer", "sunny"));
        McpToolExecutionService service = service(List.of(connector));

        McpToolCallResult result = service.callTool(id,
                new McpToolInvocation("search", Map.of("q", "weather"), Duration.ofSeconds(1)));

        assertThat(result.outcome()).isEqualTo(McpOperationOutcome.SUCCESS);
        assertThat(result.value()).isEqualTo(Map.of("answer", "sunny"));
        ArgumentCaptor<McpConnectorTarget> target = ArgumentCaptor.forClass(McpConnectorTarget.class);
        verify(connector).callTool(target.capture(), eq("search"), eq(Map.of("q", "weather")), any());
        assertThat(target.getValue().serverId()).isEqualTo(id);
        assertThat(target.getValue().endpoint().toString()).isEqualTo(server.endpoint());
        assertThat(McpConnectorTarget.class.getDeclaredFields()).extracting(field -> field.getName())
                .doesNotContain("credentialRef");
        assertAudit("MCP_TOOL_CALL", "SUCCESS", "SUCCESS");
        verify(auditRecorder, never()).record(org.mockito.ArgumentMatchers.argThat(argThatContains("secret/mcp")));
    }

    @Test
    void connectorTimeoutIsCancelledAndClassified() throws Exception {
        UUID id = UUID.randomUUID();
        McpServerRecord server = server(id, true, Set.of("search"));
        when(serverService.get(id)).thenReturn(server);
        CountDownLatch entered = new CountDownLatch(1);
        when(connector.supports(McpTransport.SSE)).thenReturn(true);
        doAnswer(invocation -> {
            entered.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return "late";
        }).when(connector).callTool(any(), eq("search"), any(), any());
        McpToolExecutionService service = service(List.of(connector));

        McpToolCallResult result = service.callTool(id,
                new McpToolInvocation("search", Map.of(), Duration.ofMillis(25)));

        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(result.outcome()).isEqualTo(McpOperationOutcome.TIMEOUT);
        assertAudit("MCP_TOOL_CALL", "TIMEOUT", "CONNECTOR_TIMEOUT");
    }

    @Test
    void connectorExceptionIsClassifiedWithoutLeakingMessage() {
        UUID id = UUID.randomUUID();
        McpServerRecord server = server(id, true, Set.of("search"));
        when(serverService.get(id)).thenReturn(server);
        when(connector.supports(McpTransport.SSE)).thenReturn(true);
        when(connector.callTool(any(), eq("search"), any(), any()))
                .thenThrow(new IllegalStateException("secret/mcp leaked by adapter"));
        McpToolExecutionService service = service(List.of(connector));

        McpToolCallResult result = service.callTool(id,
                new McpToolInvocation("search", Map.of(), Duration.ofSeconds(1)));

        assertThat(result.outcome()).isEqualTo(McpOperationOutcome.CONNECTOR_ERROR);
        assertThat(result.classification()).isEqualTo("CONNECTOR_EXCEPTION");
        assertThat(result.value()).isNull();
        assertAudit("MCP_TOOL_CALL", "CONNECTOR_ERROR", "CONNECTOR_EXCEPTION");
        verify(auditRecorder, never()).record(org.mockito.ArgumentMatchers.argThat(argThatContains("secret/mcp")));
    }

    @Test
    void discoveryIsPolicyGatedAndAudited() {
        UUID id = UUID.randomUUID();
        McpServerRecord server = server(id, true, Set.of("search"));
        when(serverService.get(id)).thenReturn(server);
        when(connector.supports(McpTransport.SSE)).thenReturn(true);
        when(connector.discoverTools(any(), any())).thenReturn(List.of(new McpToolDescriptor("search", "Search", "{}")));
        McpToolExecutionService service = service(List.of(connector));

        McpToolCallResult result = service.discoverTools(id, Duration.ofSeconds(1));

        assertThat(result.outcome()).isEqualTo(McpOperationOutcome.SUCCESS);
        assertThat(result.value()).isEqualTo(List.of(new McpToolDescriptor("search", "Search", "{}")));
        assertAudit("MCP_TOOL_DISCOVERY", "SUCCESS", "SUCCESS");
    }

    @Test
    void callsAndDiscoveryAreBothCircuitGuarded() {
        UUID callId = UUID.randomUUID();
        UUID discoveryId = UUID.randomUUID();
        when(serverService.get(callId)).thenReturn(server(callId, true, Set.of("search")));
        when(serverService.get(discoveryId)).thenReturn(server(discoveryId, true, Set.of()));
        when(connector.supports(McpTransport.SSE)).thenReturn(true);
        doThrow(new IllegalStateException("upstream unavailable"))
                .when(connector).callTool(any(), eq("search"), any(), any());
        doThrow(new IllegalStateException("upstream unavailable"))
                .when(connector).discoverTools(any(), any());
        McpRuntimeGuard guard = new McpRuntimeGuard(Clock.fixed(NOW, ZoneOffset.UTC), 4, 1, Duration.ofMinutes(1));
        McpToolExecutionService service = service(List.of(connector), guard);

        assertThat(service.callTool(callId, new McpToolInvocation("search", Map.of(), Duration.ofSeconds(1)))
                .classification()).isEqualTo("CONNECTOR_EXCEPTION");
        assertThat(service.callTool(callId, new McpToolInvocation("search", Map.of(), Duration.ofSeconds(1)))
                .classification()).isEqualTo(McpRuntimeGuard.CIRCUIT_OPEN);
        assertThat(service.discoverTools(discoveryId, Duration.ofSeconds(1)).classification())
                .isEqualTo("CONNECTOR_EXCEPTION");
        assertThat(service.discoverTools(discoveryId, Duration.ofSeconds(1)).classification())
                .isEqualTo(McpRuntimeGuard.CIRCUIT_OPEN);

        verify(connector, times(1)).callTool(any(), eq("search"), any(), any());
        verify(connector, times(1)).discoverTools(any(), any());
    }

    @Test
    void cacheHitAndInvalidationUseStableAuditClassifications() {
        UUID id = UUID.randomUUID();
        McpServerRecord server = server(id, true, Set.of("search"));
        when(serverService.get(id)).thenReturn(server);
        when(connector.transport()).thenReturn(McpTransport.SSE);
        when(connector.supports(McpTransport.SSE)).thenReturn(true);
        List<McpToolDescriptor> tools = List.of(new McpToolDescriptor("search", "Search", "{}"));
        when(connector.discoverTools(any(), any())).thenReturn(tools);
        executor = Executors.newSingleThreadExecutor();

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        McpObservability observability = new McpObservability(registry);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        McpToolDiscoveryCache cache = new McpToolDiscoveryCache(clock, Duration.ofMinutes(1), 4, observability);
        McpRuntimeGuard guard = new McpRuntimeGuard(clock, 4, 2, Duration.ofMinutes(1), observability);
        McpToolExecutionService service = new McpToolExecutionService(serverService,
                new McpRuntimePolicyService(), new McpTransportConnectorRegistry(List.of(connector)), auditRecorder,
                clock, executor, cache, guard, observability);

        assertThat(service.discoverTools(id, Duration.ofSeconds(1)).outcome()).isEqualTo(McpOperationOutcome.SUCCESS);
        assertThat(service.discoverTools(id, Duration.ofSeconds(1)).outcome()).isEqualTo(McpOperationOutcome.SUCCESS);
        service.invalidateToolDiscoveryCache(id);

        assertThat(registry.get("agentteams.mcp.tools.list.cache").tag("event", "hit").counter().count())
                .isEqualTo(1);
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder, times(3)).record(audit.capture());
        assertThat(audit.getAllValues()).anySatisfy(event -> assertThat(event.attributes())
                .containsEntry("classification", "CACHE_HIT"));
        assertThat(audit.getAllValues()).anySatisfy(event -> assertThat(event.action())
                .isEqualTo("MCP_TOOL_DISCOVERY_CACHE"));
    }

    private McpToolExecutionService service(List<McpTransportConnector> connectors) {
        executor = Executors.newSingleThreadExecutor();
        when(connector.transport()).thenReturn(McpTransport.SSE);
        when(connector.supports(McpTransport.SSE)).thenReturn(true);
        return new McpToolExecutionService(serverService, new McpRuntimePolicyService(), connectors, auditRecorder,
                Clock.fixed(NOW, ZoneOffset.UTC), executor);
    }

    private McpToolExecutionService service(List<McpTransportConnector> connectors, McpRuntimeGuard guard) {
        executor = Executors.newSingleThreadExecutor();
        when(connector.transport()).thenReturn(McpTransport.SSE);
        when(connector.supports(McpTransport.SSE)).thenReturn(true);
        return new McpToolExecutionService(serverService, new McpRuntimePolicyService(),
                new McpTransportConnectorRegistry(connectors), auditRecorder,
                Clock.fixed(NOW, ZoneOffset.UTC), executor,
                new McpToolDiscoveryCache(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(1), 4), guard);
    }

    private McpToolExecutionService service(McpTransportConnectorRegistry registry) {
        executor = Executors.newSingleThreadExecutor();
        return new McpToolExecutionService(serverService, new McpRuntimePolicyService(), registry, auditRecorder,
                Clock.fixed(NOW, ZoneOffset.UTC), executor);
    }

    private void assertAudit(String action, String result, String classification) {
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder, org.mockito.Mockito.atLeastOnce()).record(audit.capture());
        AuditEvent event = audit.getAllValues().stream().filter(candidate -> candidate.action().equals(action))
                .reduce((first, second) -> second).orElseThrow();
        assertThat(event.attributes()).containsEntry("result", result)
                .containsEntry("classification", classification)
                .doesNotContainKey("credentialRef");
    }

    private static AuditEventMatcher argThatContains(String value) {
        return new AuditEventMatcher(value);
    }

    private static McpServerRecord server(UUID id, boolean enabled, Set<String> tools) {
        return new McpServerRecord(id, "weather", McpTransport.SSE, "https://mcp.example.test/sse", "secret/mcp",
                enabled, McpHealthStatus.HEALTHY, NOW, NOW, NOW, 0,
                new OutboundPolicy(Set.of("https"), Set.of("mcp.example.test"), Duration.ofSeconds(5), tools));
    }

    private static final class AuditEventMatcher implements org.mockito.ArgumentMatcher<AuditEvent> {
        private final String value;

        private AuditEventMatcher(String value) {
            this.value = value;
        }

        @Override
        public boolean matches(AuditEvent event) {
            return event != null && (event.resourceId().contains(value) || event.attributes().containsValue(value)
                    || event.actor().contains(value) || event.action().contains(value));
        }
    }
}
