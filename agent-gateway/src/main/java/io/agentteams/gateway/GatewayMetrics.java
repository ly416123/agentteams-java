package io.agentteams.gateway;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Stable low-cardinality Gateway metrics. */
public final class GatewayMetrics implements GatewayMetricsPort {
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final Counter opened;
    private final Counter closed;
    private final Counter registered;
    private final Counter rejected;

    public GatewayMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        Gauge.builder("agentteams.gateway.connections.active", activeConnections, AtomicInteger::get)
                .description("Active Gateway streams").register(registry);
        opened = registry.counter("agentteams.gateway.connections.opened");
        closed = registry.counter("agentteams.gateway.connections.closed");
        registered = registry.counter("agentteams.gateway.connections.registered");
        rejected = registry.counter("agentteams.gateway.events.rejected");
    }

    @Override
    public void connectionOpened() { activeConnections.incrementAndGet(); opened.increment(); }

    @Override
    public void connectionClosed() {
        activeConnections.updateAndGet(value -> Math.max(0, value - 1));
        closed.increment();
    }

    @Override
    public void connectionRegistered() { registered.increment(); }

    @Override
    public void eventRejected() { rejected.increment(); }
}
