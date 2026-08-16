package io.agentteams.controlplane.health;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("nats")
public final class NatsHealthIndicator implements HealthIndicator {

    private final ObjectProvider<NatsConnectionProbe> probeProvider;

    public NatsHealthIndicator(ObjectProvider<NatsConnectionProbe> probeProvider) {
        this.probeProvider = probeProvider;
    }

    @Override
    public Health health() {
        NatsConnectionProbe probe = probeProvider.getIfAvailable();
        if (probe == null) {
            return Health.down().withDetail("reason", "NATS connection probe is not configured").build();
        }
        try {
            if (probe.isConnected()) {
                return Health.up().build();
            }
            return Health.down().withDetail("reason", "NATS connection is not connected").build();
        } catch (RuntimeException ignored) {
            return Health.down().withDetail("reason", "NATS connection probe failed").build();
        }
    }
}
