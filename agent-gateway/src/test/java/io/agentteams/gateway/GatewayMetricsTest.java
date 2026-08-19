package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class GatewayMetricsTest {
    @Test
    void recordsConnectionAndCommandRecoveryCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayMetrics metrics = new GatewayMetrics(registry);

        metrics.connectionOpened();
        metrics.connectionRegistered();
        metrics.commandAppended();
        metrics.commandDeduplicated();
        metrics.eventRejected();
        metrics.connectionClosed();

        assertThat(registry.find("agentteams.gateway.connections.active").gauge().value()).isZero();
        assertThat(registry.counter("agentteams.gateway.commands.appended").count()).isEqualTo(1);
        assertThat(registry.counter("agentteams.gateway.commands.deduplicated").count()).isEqualTo(1);
        assertThat(registry.counter("agentteams.gateway.events.rejected").count()).isEqualTo(1);
    }
}
