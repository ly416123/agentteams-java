package io.agentteams.controlplane.mcp;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Deterministic MCP connector registry and transport selector.
 *
 * <p>Concrete connectors are registered for every transport they support. A default connector is
 * kept separately as a fallback, which lets the safe validation connector coexist with an opt-in
 * concrete connector. There can be at most one connector for each transport in either tier.</p>
 */
@Component
public final class McpTransportConnectorRegistry {
    private final Map<McpTransport, McpTransportConnector> connectors = new EnumMap<>(McpTransport.class);
    private final Map<McpTransport, McpTransportConnector> fallbackConnectors =
            new EnumMap<>(McpTransport.class);

    @Autowired
    public McpTransportConnectorRegistry(List<McpTransportConnector> connectors) {
        Objects.requireNonNull(connectors, "connectors").forEach(this::register);
    }

    public McpTransportConnectorRegistry() {
    }

    public McpTransportConnectorRegistry(Iterable<? extends McpTransportConnector> connectors) {
        Objects.requireNonNull(connectors, "connectors");
        connectors.forEach(this::register);
    }

    /** Registers a connector for every transport it declares support for. */
    public synchronized void register(McpTransportConnector connector) {
        Objects.requireNonNull(connector, "connector");
        McpTransport declaredTransport = Objects.requireNonNull(connector.transport(),
                "connector.transport()");
        if (!connector.supports(declaredTransport)) {
            throw new IllegalArgumentException("connector must support its declared transport "
                    + declaredTransport);
        }

        EnumSet<McpTransport> supportedTransports = EnumSet.noneOf(McpTransport.class);
        for (McpTransport transport : McpTransport.values()) {
            if (connector.supports(transport)) {
                supportedTransports.add(transport);
            }
        }
        if (supportedTransports.isEmpty()) {
            throw new IllegalArgumentException("connector must support at least one MCP transport");
        }

        Map<McpTransport, McpTransportConnector> target = connector.isFallback()
                ? fallbackConnectors : connectors;
        for (McpTransport transport : supportedTransports) {
            if (target.containsKey(transport)) {
                throw new IllegalStateException("duplicate MCP connector registration for transport "
                        + transport);
            }
        }
        for (McpTransport transport : supportedTransports) {
            target.put(transport, connector);
        }
    }

    /** Returns the concrete connector, or the default connector when no concrete one is registered. */
    public synchronized McpTransportConnector select(McpTransport transport) {
        Objects.requireNonNull(transport, "transport");
        McpTransportConnector connector = connectors.get(transport);
        return connector != null ? connector : fallbackConnectors.get(transport);
    }

    public synchronized boolean supports(McpTransport transport) {
        return select(transport) != null;
    }
}
