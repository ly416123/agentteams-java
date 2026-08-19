package io.agentteams.gateway;

import com.google.protobuf.Timestamp;
import io.agentteams.contracts.v1.Ack;
import io.agentteams.contracts.v1.AgentMessage;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.ServerMessage;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Validates current-session inbound events, deduplicates them durably, and routes them to the application seam. */
public final class InboundEventHandler {

    private final ConnectionRegistry registry;
    private final InboundEventPort events;
    private final GatewayApplicationHandler application;
    private final CommandDeliveryService delivery;
    private final Clock clock;

    public InboundEventHandler(ConnectionRegistry registry, InboundEventPort events,
            GatewayApplicationHandler application, CommandDeliveryService delivery, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.events = Objects.requireNonNull(events, "events");
        this.application = Objects.requireNonNull(application, "application");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void handle(AgentConnection connection, AgentMessage message) {
        ConnectionRegistry.ConnectionSnapshot snapshot = current(connection);
        if (message == null || message.getPayloadCase() == AgentMessage.PayloadCase.PAYLOAD_NOT_SET) {
            throw new GatewayExceptions.InvalidMessage("agent message payload is required");
        }
        if (message.getPayloadCase() == AgentMessage.PayloadCase.ACK) {
            handleAck(connection, snapshot, message.getAck());
            return;
        }
        EventMetadata metadata = metadata(message);
        validateMetadata(snapshot, metadata);
        Instant receivedAt = clock.instant();
        // This handler owns the in-memory lastSeen update; AgentChannelService mirrors it to AgentStatePort.
        ConnectionRegistry.ConnectionSnapshot seenConnection = registry.touch(connection, receivedAt)
                .orElseThrow(() -> new GatewayExceptions.StaleConnection("connection became stale"));
        boolean first = events.recordIfNew(metadata.getEventId(), snapshot.agentId(), snapshot.connectionId(), receivedAt);
        if (first) {
            route(seenConnection, message);
        }
        sendAck(connection, metadata, receivedAt);
    }

    private void handleAck(AgentConnection connection, ConnectionRegistry.ConnectionSnapshot snapshot, Ack ack) {
        EventMetadata metadata = ack.getMetadata();
        validateMetadata(snapshot, metadata);
        if (ack.getAckedEventId().isBlank()) {
            throw new InvalidAcknowledgementException("acked event_id is required");
        }
        delivery.acknowledge(connection, ack.getAckedSequence());
    }

    private void sendAck(AgentConnection connection, EventMetadata metadata, Instant at) {
        EventMetadata ackMetadata = metadata.toBuilder()
                .setEventId("ack-" + metadata.getEventId())
                .setOccurredAt(Timestamp.newBuilder().setSeconds(at.getEpochSecond())
                        .setNanos(at.getNano()).build())
                .build();
        connection.outbound().onNext(ServerMessage.newBuilder().setAck(Ack.newBuilder()
                .setMetadata(ackMetadata)
                .setAckedEventId(metadata.getEventId())
                .setAckedSequence(metadata.getSequence())
                .build()).build());
    }

    private void route(ConnectionRegistry.ConnectionSnapshot snapshot, AgentMessage message) {
        switch (message.getPayloadCase()) {
            case TASK_ACCEPTED -> application.taskAccepted(snapshot, message.getTaskAccepted());
            case TASK_PROGRESS -> application.taskProgress(snapshot, message.getTaskProgress());
            case TASK_HEARTBEAT -> application.taskHeartbeat(snapshot, message.getTaskHeartbeat());
            case TASK_COMPLETED -> application.taskCompleted(snapshot, message.getTaskCompleted());
            case TASK_FAILED -> application.taskFailed(snapshot, message.getTaskFailed());
            case AGENT_HEARTBEAT -> application.agentHeartbeat(snapshot, message.getAgentHeartbeat());
            case CONFIG_APPLIED -> throw new GatewayExceptions.InvalidMessage(
                    "ConfigApplied is not handled by the Task Gateway application port");
            case ERROR -> throw new GatewayExceptions.InvalidMessage("agent reported an Error payload");
            case HELLO, ACK, PAYLOAD_NOT_SET -> throw new GatewayExceptions.InvalidMessage(
                    "payload is not an inbound execution event");
        }
    }

    private ConnectionRegistry.ConnectionSnapshot current(AgentConnection connection) {
        if (!registry.isCurrent(connection)) {
            throw new GatewayExceptions.StaleConnection("message came from a stale connection");
        }
        return registry.snapshot(connection).orElseThrow();
    }

    private static EventMetadata metadata(AgentMessage message) {
        return switch (message.getPayloadCase()) {
            case TASK_ACCEPTED -> message.getTaskAccepted().getMetadata();
            case TASK_PROGRESS -> message.getTaskProgress().getMetadata();
            case TASK_HEARTBEAT -> message.getTaskHeartbeat().getMetadata();
            case TASK_COMPLETED -> message.getTaskCompleted().getMetadata();
            case TASK_FAILED -> message.getTaskFailed().getMetadata();
            case AGENT_HEARTBEAT -> message.getAgentHeartbeat().getMetadata();
            case ACK -> message.getAck().getMetadata();
            default -> throw new GatewayExceptions.InvalidMessage("payload metadata is required");
        };
    }

    private static void validateMetadata(ConnectionRegistry.ConnectionSnapshot snapshot, EventMetadata metadata) {
        if (metadata.getEventId().isBlank()) {
            throw new GatewayExceptions.InvalidMessage("event_id is required");
        }
        if (metadata.getAgentId().isBlank() || !snapshot.agentId().equals(metadata.getAgentId())) {
            throw new GatewayExceptions.InvalidMessage("event agent_id does not match the connection");
        }
    }
}
