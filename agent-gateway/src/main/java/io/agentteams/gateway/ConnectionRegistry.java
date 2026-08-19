package io.agentteams.gateway;

import io.grpc.stub.StreamObserver;
import io.agentteams.contracts.v1.ServerMessage;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks active stream metadata only; durable task and command state lives behind ports. */
public final class ConnectionRegistry {

    private final Map<UUID, AgentConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, AgentConnection> currentByAgent = new ConcurrentHashMap<>();
    private final ConnectionTermination termination;
    private final GatewayMetricsPort metrics;

    public ConnectionRegistry() {
        this(ConnectionTermination.grpcStream(), GatewayMetricsPort.noop());
    }

    public ConnectionRegistry(ConnectionTermination termination) {
        this(termination, GatewayMetricsPort.noop());
    }

    public ConnectionRegistry(ConnectionTermination termination, GatewayMetricsPort metrics) {
        this.termination = Objects.requireNonNull(termination, "termination");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public AgentConnection open(StreamObserver<ServerMessage> outbound, String transportIdentity, Instant connectedAt) {
        AgentConnection connection = new AgentConnection(UUID.randomUUID(), outbound, transportIdentity, connectedAt);
        connections.put(connection.connectionId(), connection);
        metrics.connectionOpened();
        return connection;
    }

    /** Registers after Hello/authentication/negotiation; replacing a connection makes the old one stale. */
    public Optional<ConnectionSnapshot> register(AgentConnection connection, AgentProfile profile,
            long initialAckSequence, Instant seenAt) {
        requireOwned(connection);
        Objects.requireNonNull(profile, "profile");
        connection.register(profile, initialAckSequence, seenAt);
        metrics.connectionRegistered();
        AgentConnection replaced = currentByAgent.put(profile.agentId(), connection);
        if (replaced == null || replaced == connection) {
            return Optional.empty();
        }
        try {
            termination.terminate(replaced, ConnectionTermination.Termination.stale());
        } finally {
            // The termination callback closes the peer stream, but it does not
            // necessarily call the server-side inbound observer. Remove the
            // replaced handle here so a reconnect cannot leave a ghost stream
            // in this replica's local registry.
            close(replaced);
        }
        return snapshot(replaced);
    }

    public Optional<AgentConnection> current(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(currentByAgent.get(agentId));
    }

    public boolean isCurrent(AgentConnection connection) {
        return connection != null && connection.profile().map(profile ->
                currentByAgent.get(profile.agentId()) == connection).orElse(false);
    }

    public Optional<ConnectionSnapshot> snapshot(AgentConnection connection) {
        if (connection == null || connection.profile().isEmpty()) {
            return Optional.empty();
        }
        AgentProfile profile = connection.profile().orElseThrow();
        return Optional.of(new ConnectionSnapshot(connection.connectionId(), profile.agentId(), profile.runtime(),
                profile.runtimeVersion(), profile.capabilities(), connection.lastSeen(), connection.lastAckSequence()));
    }

    public Optional<ConnectionSnapshot> touch(AgentConnection connection, Instant at) {
        if (!isCurrent(connection)) {
            return Optional.empty();
        }
        connection.seenAt(at);
        return snapshot(connection);
    }

    public boolean acknowledge(AgentConnection connection, long sequence, Instant at) {
        if (!isCurrent(connection)) {
            return false;
        }
        connection.acknowledge(sequence);
        connection.seenAt(at);
        return true;
    }

    /** Removes only this stream. A stale stream can never remove a newer current stream. */
    public Optional<ConnectionSnapshot> close(AgentConnection connection) {
        if (connection == null) {
            return Optional.empty();
        }
        boolean removed = connections.remove(connection.connectionId(), connection);
        if (removed) {
            metrics.connectionClosed();
        }
        Optional<AgentProfile> profile = connection.profile();
        if (profile.isEmpty() || !currentByAgent.remove(profile.get().agentId(), connection)) {
            return Optional.empty();
        }
        return snapshot(connection);
    }

    private void requireOwned(AgentConnection connection) {
        Objects.requireNonNull(connection, "connection");
        if (connections.get(connection.connectionId()) != connection) {
            throw new IllegalArgumentException("connection does not belong to this registry");
        }
    }

    public record ConnectionSnapshot(
            UUID connectionId,
            String agentId,
            String runtime,
            String runtimeVersion,
            Map<String, String> capabilities,
            Instant lastSeen,
            long lastAckSequence) {

        public ConnectionSnapshot {
            Objects.requireNonNull(connectionId, "connectionId");
            requireText(agentId, "agentId");
            requireText(runtime, "runtime");
            requireText(runtimeVersion, "runtimeVersion");
            capabilities = Map.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
            Objects.requireNonNull(lastSeen, "lastSeen");
            if (lastAckSequence < 0) {
                throw new IllegalArgumentException("lastAckSequence must not be negative");
            }
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }
}
