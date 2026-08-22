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
    private final Counter replaced;
    private final Counter rejected;
    private final Counter commandsAppended;
    private final Counter commandsDeduplicated;
    private final Counter commandsReplayed;
    private final Counter natsEventsProcessed;
    private final Counter natsEventsRejected;
    private final Counter natsConsumerErrors;

    public GatewayMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        Gauge.builder("agentteams.gateway.connections.active", activeConnections, AtomicInteger::get)
                .description("Active Gateway streams").register(registry);
        opened = registry.counter("agentteams.gateway.connections.opened");
        closed = registry.counter("agentteams.gateway.connections.closed");
        registered = registry.counter("agentteams.gateway.connections.registered");
        replaced = registry.counter("agentteams.gateway.connections.replaced");
        rejected = registry.counter("agentteams.gateway.events.rejected");
        commandsAppended = registry.counter("agentteams.gateway.commands.appended");
        commandsDeduplicated = registry.counter("agentteams.gateway.commands.deduplicated");
        commandsReplayed = registry.counter("agentteams.gateway.commands.replayed");
        natsEventsProcessed = registry.counter("agentteams.gateway.nats.events.processed");
        natsEventsRejected = registry.counter("agentteams.gateway.nats.events.rejected");
        natsConsumerErrors = registry.counter("agentteams.gateway.nats.consumer.errors");
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
    public void connectionReplaced() { replaced.increment(); }

    @Override
    public void eventRejected() { rejected.increment(); }

    @Override
    public void commandAppended() { commandsAppended.increment(); }

    @Override
    public void commandDeduplicated() { commandsDeduplicated.increment(); }

    @Override
    public void commandReplayed() { commandsReplayed.increment(); }

    @Override
    public void natsEventProcessed() { natsEventsProcessed.increment(); }

    @Override
    public void natsEventRejected() { natsEventsRejected.increment(); }

    @Override
    public void natsConsumerError() { natsConsumerErrors.increment(); }
}
