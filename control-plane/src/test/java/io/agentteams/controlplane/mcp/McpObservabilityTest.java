package io.agentteams.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class McpObservabilityTest {
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void guardExposesOnlyStableRejectionAndCircuitMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        McpObservability observability = new McpObservability(registry);
        McpRuntimeGuard guard = new McpRuntimeGuard(Clock.fixed(NOW, ZoneOffset.UTC), 1, 1,
                Duration.ofSeconds(10), observability);
        UUID serverId = UUID.randomUUID();

        McpRuntimeGuard.Lease lease = guard.tryAcquire(serverId);
        assertThat(guard.tryAcquire(serverId).rejection()).isEqualTo(McpRuntimeGuard.RATE_LIMITED);
        lease.failure();
        assertThat(guard.tryAcquire(serverId).rejection()).isEqualTo(McpRuntimeGuard.CIRCUIT_OPEN);

        assertThat(registry.get("agentteams.mcp.runtime.rejections").tag("reason", McpRuntimeGuard.RATE_LIMITED)
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("agentteams.mcp.runtime.rejections").tag("reason", McpRuntimeGuard.CIRCUIT_OPEN)
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("agentteams.mcp.runtime.circuit.opened").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allMatch(meter -> meter.getId().getTags().stream()
                .noneMatch(tag -> tag.getValue().contains(serverId.toString())));
    }

    @Test
    void cacheExposesLifecycleEventsWithoutServerIdentity() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        McpObservability observability = new McpObservability(registry);
        McpToolDiscoveryCache cache = new McpToolDiscoveryCache(Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(1), 1, observability);
        UUID serverId = UUID.randomUUID();
        List<McpToolDescriptor> tools = List.of(new McpToolDescriptor("search", "", "{}"));

        assertThat(cache.get(serverId, 1)).isNull();
        cache.put(serverId, 1, tools);
        assertThat(cache.get(serverId, 1)).isEqualTo(tools);
        cache.invalidate(serverId);

        assertThat(registry.get("agentteams.mcp.tools.list.cache").tag("event", "miss").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("agentteams.mcp.tools.list.cache").tag("event", "hit").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("agentteams.mcp.tools.list.cache").tag("event", "invalidated").counter().count())
                .isEqualTo(1);
        assertThat(registry.getMeters()).allMatch(meter -> meter.getId().getTags().stream()
                .noneMatch(tag -> tag.getValue().contains(serverId.toString())));
    }

    @Test
    void connectorMetricsBoundFailureClassificationAndOperation() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        McpObservability observability = new McpObservability(registry);

        observability.connectorFailed("tool_call", McpHttpFailureCategory.UPSTREAM_5XX.name(), null);
        observability.connectorTimedOut("tools_list", null);

        assertThat(registry.get("agentteams.mcp.connector.calls")
                .tags("operation", "tool_call", "outcome", "failure", "classification", "UPSTREAM_5XX")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("agentteams.mcp.connector.calls")
                .tags("operation", "tools_list", "outcome", "timeout", "classification", "CONNECTOR_TIMEOUT")
                .counter().count()).isEqualTo(1);
    }
}
