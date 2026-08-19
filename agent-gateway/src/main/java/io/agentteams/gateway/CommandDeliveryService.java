package io.agentteams.gateway;

import io.agentteams.contracts.v1.ServerMessage;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Persists commands first, then pushes them to the current stream or replays them after reconnect. */
public final class CommandDeliveryService {

    private final ConnectionRegistry registry;
    private final CommandReplayPort commands;
    private final Clock clock;

    public CommandDeliveryService(ConnectionRegistry registry, CommandReplayPort commands, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Appends an assignment to durable storage before attempting an active-stream delivery. */
    public SequencedCommand deliver(String agentId, ServerMessage command) {
        requireAgentId(agentId);
        Objects.requireNonNull(command, "command");
        if (!command.hasTaskAssigned() && !command.hasConfigChanged()) {
            throw new IllegalArgumentException("unsupported Agent command payload");
        }
        SequencedCommand persisted = commands.append(agentId, command);
        registry.current(agentId).ifPresent(connection -> sendIfCurrent(connection, persisted));
        return persisted;
    }

    /** Replays the durable unacknowledged suffix in the store's per-Agent sequence order. */
    public int replay(AgentConnection connection) {
        ConnectionRegistry.ConnectionSnapshot snapshot = requireCurrent(connection);
        List<SequencedCommand> pending = commands.replayUnacknowledged(snapshot.agentId());
        int sent = 0;
        for (SequencedCommand command : pending) {
            if (!registry.isCurrent(connection)) {
                throw new GatewayExceptions.StaleConnection("connection was replaced during replay");
            }
            sendIfCurrent(connection, command);
            sent++;
        }
        return sent;
    }

    public long lastAcknowledgedSequence(String agentId) {
        long sequence = commands.lastAcknowledgedSequence(agentId);
        if (sequence < 0) {
            throw new IllegalStateException("durable acknowledgement sequence must not be negative");
        }
        return sequence;
    }

    /** Applies a command acknowledgement only if it came from the current stream. */
    public boolean acknowledge(AgentConnection connection, long sequence) {
        ConnectionRegistry.ConnectionSnapshot before = requireCurrent(connection);
        if (sequence <= 0) {
            throw new InvalidAcknowledgementException("acknowledged sequence must be positive");
        }
        AcknowledgementValidation validation = commands.validateAcknowledgement(
                before.agentId(), before.connectionId(), sequence);
        if (validation == null || !validation.accepted()) {
            String reason = validation == null ? "durable acknowledgement validation returned no result"
                    : validation.rejectionReason();
            throw new InvalidAcknowledgementException(reason);
        }
        boolean advancesCursor = sequence > before.lastAckSequence();
        Instant now = clock.instant();
        if (!registry.acknowledge(connection, sequence, now)) {
            throw new GatewayExceptions.StaleConnection("acknowledgement came from a stale connection");
        }
        if (advancesCursor) {
            commands.acknowledge(before.agentId(), sequence);
        }
        return true;
    }

    private void sendIfCurrent(AgentConnection connection, SequencedCommand command) {
        if (!registry.isCurrent(connection)) {
            throw new GatewayExceptions.StaleConnection("command target is no longer current");
        }
        StreamObserver<ServerMessage> outbound = connection.outbound();
        outbound.onNext(command.message());
        commands.markDelivered(connection.profile().orElseThrow().agentId(), connection.connectionId(),
                command.sequence());
    }

    private ConnectionRegistry.ConnectionSnapshot requireCurrent(AgentConnection connection) {
        if (!registry.isCurrent(connection)) {
            throw new GatewayExceptions.StaleConnection("connection is not current");
        }
        return registry.snapshot(connection).orElseThrow();
    }

    private static void requireAgentId(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
    }
}
