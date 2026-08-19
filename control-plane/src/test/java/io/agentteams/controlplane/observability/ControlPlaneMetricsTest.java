package io.agentteams.controlplane.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ControlPlaneMetricsTest {
    @Test
    void recordsStableCountersAndManagerLatency() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ControlPlaneMetrics metrics = new ControlPlaneMetrics(registry);
        metrics.taskCreated();
        metrics.taskAssigned();
        metrics.taskCompleted();
        metrics.taskFailed();
        metrics.gatewayReconnected();
        metrics.outboxRetried();
        metrics.taskLeaseExpired();
        metrics.taskLeaseReleased();
        metrics.managerCall(Duration.ofMillis(20));
        assertThat(registry.counter("agentteams.tasks.created").count()).isEqualTo(1);
        assertThat(registry.counter("agentteams.gateway.reconnects").count()).isEqualTo(1);
        assertThat(registry.counter("agentteams.tasks.leases.expired").count()).isEqualTo(1);
        assertThat(registry.counter("agentteams.tasks.leases.released").count()).isEqualTo(1);
        assertThat(registry.timer("agentteams.manager.call.latency").count()).isEqualTo(1);
    }
}
