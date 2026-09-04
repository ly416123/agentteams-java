package io.agentteams.observability;

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
        metrics.outboxPublished();
        metrics.outboxDeadLettered();
        metrics.outboxPublishFailed();
        metrics.outboxBacklog(3);
        metrics.outboxOldestPendingAge(Duration.ofSeconds(42));
        metrics.outboxPublish(Duration.ofMillis(12));
        metrics.taskLeaseExpired();
        metrics.taskLeaseReleased();
        metrics.managerCall(Duration.ofMillis(20));
        assertThat(registry.counter("agentteams.tasks.created").count()).isEqualTo(1);
        assertThat(registry.counter("agentteams.gateway.reconnects").count()).isEqualTo(1);
        assertThat(registry.counter("agentteams.tasks.leases.expired").count()).isEqualTo(1);
        assertThat(registry.counter("agentteams.tasks.leases.released").count()).isEqualTo(1);
        assertThat(registry.timer("agentteams.manager.call.latency").count()).isEqualTo(1);
        assertThat(registry.counter("agentteams.outbox.published").count()).isEqualTo(1);
        assertThat(registry.counter("agentteams.outbox.dead_lettered").count()).isEqualTo(1);
        assertThat(registry.counter("agentteams.outbox.publish.failures").count()).isEqualTo(1);
        assertThat(registry.find("agentteams.outbox.backlog").gauge().value()).isEqualTo(3);
        assertThat(registry.find("agentteams.outbox.oldest.pending.age.seconds").gauge().value()).isEqualTo(42);
        assertThat(registry.timer("agentteams.outbox.publish.latency").count()).isEqualTo(1);
    }
}
