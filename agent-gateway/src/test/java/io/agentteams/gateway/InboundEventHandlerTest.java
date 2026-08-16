package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.contracts.v1.AgentMessage;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InboundEventHandlerTest {

    @Test
    void persistsAcceptedEventIdBeforeForwardingToApplication() {
        ConnectionRegistry registry = new ConnectionRegistry();
        GatewayTestFixtures.RecordingInboundStore eventStore = new GatewayTestFixtures.RecordingInboundStore();
        GatewayTestFixtures.RecordingApplicationHandler application =
                new GatewayTestFixtures.RecordingApplicationHandler();
        CommandDeliveryService delivery = new CommandDeliveryService(registry,
                new GatewayTestFixtures.RecordingCommandStore(), fixedClock());
        InboundEventHandler handler = new InboundEventHandler(registry, eventStore, application, delivery, fixedClock());
        AgentConnection connection = connected(registry, "agent-1");

        handler.handle(connection, GatewayTestFixtures.accepted("agent-1", "accepted-1"));

        assertThat(eventStore.seen).containsExactly("accepted-1");
        assertThat(eventStore.agents).containsExactly("agent-1");
        assertThat(application.accepted).extracting(event -> event.getMetadata().getEventId())
                .containsExactly("accepted-1");
    }

    @Test
    void forwardsProgressToPortAndApplication() {
        GatewayTestFixtures.RecordingInboundStore eventStore = new GatewayTestFixtures.RecordingInboundStore();
        GatewayTestFixtures.RecordingApplicationHandler application = new GatewayTestFixtures.RecordingApplicationHandler();
        AgentMessage message = GatewayTestFixtures.progress("agent-1", "progress-1");
        ConnectionRegistry registry = new ConnectionRegistry();
        InboundEventHandler handler = handler(registry, eventStore, application);

        handler.handle(connected(registry, "agent-1"), message);

        assertThat(eventStore.seen).containsExactly("progress-1");
        assertThat(eventStore.agents).containsExactly("agent-1");
        assertThat(application.progress).containsExactly(message.getTaskProgress());
    }

    @Test
    void forwardsHeartbeatToPortAndApplication() {
        GatewayTestFixtures.RecordingInboundStore eventStore = new GatewayTestFixtures.RecordingInboundStore();
        GatewayTestFixtures.RecordingApplicationHandler application = new GatewayTestFixtures.RecordingApplicationHandler();
        AgentMessage message = GatewayTestFixtures.heartbeat("agent-1", "heartbeat-1");
        ConnectionRegistry registry = new ConnectionRegistry();
        InboundEventHandler handler = handler(registry, eventStore, application);

        handler.handle(connected(registry, "agent-1"), message);

        assertThat(eventStore.seen).containsExactly("heartbeat-1");
        assertThat(eventStore.agents).containsExactly("agent-1");
        assertThat(application.heartbeats).containsExactly(message.getTaskHeartbeat());
    }

    @Test
    void forwardsCompletedToPortAndApplication() {
        GatewayTestFixtures.RecordingInboundStore eventStore = new GatewayTestFixtures.RecordingInboundStore();
        GatewayTestFixtures.RecordingApplicationHandler application = new GatewayTestFixtures.RecordingApplicationHandler();
        AgentMessage message = GatewayTestFixtures.completed("agent-1", "completed-1");
        ConnectionRegistry registry = new ConnectionRegistry();
        InboundEventHandler handler = handler(registry, eventStore, application);

        handler.handle(connected(registry, "agent-1"), message);

        assertThat(eventStore.seen).containsExactly("completed-1");
        assertThat(eventStore.agents).containsExactly("agent-1");
        assertThat(application.completed).containsExactly(message.getTaskCompleted());
    }

    @Test
    void forwardsFailedToPortAndApplication() {
        GatewayTestFixtures.RecordingInboundStore eventStore = new GatewayTestFixtures.RecordingInboundStore();
        GatewayTestFixtures.RecordingApplicationHandler application = new GatewayTestFixtures.RecordingApplicationHandler();
        AgentMessage message = GatewayTestFixtures.failed("agent-1", "failed-1");
        ConnectionRegistry registry = new ConnectionRegistry();
        InboundEventHandler handler = handler(registry, eventStore, application);

        handler.handle(connected(registry, "agent-1"), message);

        assertThat(eventStore.seen).containsExactly("failed-1");
        assertThat(eventStore.agents).containsExactly("agent-1");
        assertThat(application.failed).containsExactly(message.getTaskFailed());
    }

    @Test
    void ignoresDuplicateInboundEventId() {
        ConnectionRegistry registry = new ConnectionRegistry();
        GatewayTestFixtures.RecordingInboundStore eventStore = new GatewayTestFixtures.RecordingInboundStore();
        GatewayTestFixtures.RecordingApplicationHandler application =
                new GatewayTestFixtures.RecordingApplicationHandler();
        CommandDeliveryService delivery = new CommandDeliveryService(registry,
                new GatewayTestFixtures.RecordingCommandStore(), fixedClock());
        InboundEventHandler handler = new InboundEventHandler(registry, eventStore, application, delivery, fixedClock());
        AgentConnection connection = connected(registry, "agent-1");
        AgentMessage accepted = GatewayTestFixtures.accepted("agent-1", "duplicate-1");

        handler.handle(connection, accepted);
        handler.handle(connection, accepted);

        assertThat(application.accepted).hasSize(1);
        assertThat(eventStore.seen).containsExactly("duplicate-1", "duplicate-1");
    }

    @Test
    void durableDedupIsSharedAcrossHandlerInstances() {
        ConnectionRegistry registry = new ConnectionRegistry();
        GatewayTestFixtures.RecordingInboundStore durableStore = new GatewayTestFixtures.RecordingInboundStore();
        GatewayTestFixtures.RecordingApplicationHandler firstApplication =
                new GatewayTestFixtures.RecordingApplicationHandler();
        GatewayTestFixtures.RecordingApplicationHandler secondApplication =
                new GatewayTestFixtures.RecordingApplicationHandler();
        AgentConnection connection = connected(registry, "agent-1");
        AgentMessage accepted = GatewayTestFixtures.accepted("agent-1", "durable-duplicate-1");

        handler(registry, durableStore, firstApplication).handle(connection, accepted);
        handler(registry, durableStore, secondApplication).handle(connection, accepted);

        assertThat(durableStore.seen).containsExactly("durable-duplicate-1", "durable-duplicate-1");
        assertThat(firstApplication.accepted).containsExactly(accepted.getTaskAccepted());
        assertThat(secondApplication.accepted).isEmpty();
    }

    @Test
    void rejectsIllegalAckAsTypedErrorWithoutCallingDurableAcknowledge() {
        ConnectionRegistry registry = new ConnectionRegistry();
        GatewayTestFixtures.RecordingInboundStore eventStore = new GatewayTestFixtures.RecordingInboundStore();
        GatewayTestFixtures.RecordingApplicationHandler application = new GatewayTestFixtures.RecordingApplicationHandler();
        GatewayTestFixtures.RecordingCommandStore commandStore = new GatewayTestFixtures.RecordingCommandStore();
        CommandDeliveryService delivery = new CommandDeliveryService(registry, commandStore, fixedClock());
        InboundEventHandler handler = new InboundEventHandler(registry, eventStore, application, delivery, fixedClock());
        AgentConnection connection = connected(registry, "agent-1");

        assertThatThrownBy(() -> handler.handle(connection, GatewayTestFixtures.ack("agent-1", "ack-0", 0)))
                .isInstanceOf(InvalidAcknowledgementException.class);

        assertThat(commandStore.acknowledged).isEmpty();
    }

    private static AgentConnection connected(ConnectionRegistry registry, String agentId) {
        AgentConnection connection = registry.open(new StreamObserver<>() {
            @Override
            public void onNext(io.agentteams.contracts.v1.ServerMessage value) {
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onCompleted() {
            }
        }, "test-peer", fixedClock().instant());
        registry.register(connection, new AgentProfile(agentId, "qwenpaw", "0.4.0", java.util.Map.of("tasks", "1")),
                0, fixedClock().instant());
        return connection;
    }

    private static InboundEventHandler handler(ConnectionRegistry registry,
            GatewayTestFixtures.RecordingInboundStore eventStore,
            GatewayTestFixtures.RecordingApplicationHandler application) {
        CommandDeliveryService delivery = new CommandDeliveryService(registry,
                new GatewayTestFixtures.RecordingCommandStore(), fixedClock());
        return new InboundEventHandler(registry, eventStore, application, delivery, fixedClock());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);
    }
}
