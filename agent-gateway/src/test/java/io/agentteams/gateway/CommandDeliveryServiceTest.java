package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.contracts.v1.AgentMessage;
import io.agentteams.contracts.v1.ServerMessage;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CommandDeliveryServiceTest {

    @Test
    void persistsAndDeliversTaskAssignedWithPerAgentSequence() {
        ConnectionRegistry registry = new ConnectionRegistry();
        GatewayTestFixtures.RecordingCommandStore store = new GatewayTestFixtures.RecordingCommandStore();
        CommandDeliveryService delivery = new CommandDeliveryService(registry, store, fixedClock());
        AgentChannelService service = service(registry, delivery, store);
        GatewayTestFixtures.RecordingObserver outbound = new GatewayTestFixtures.RecordingObserver();
        StreamObserver<AgentMessage> inbound = service.connect(outbound);
        inbound.onNext(GatewayTestFixtures.hello("agent-1"));

        delivery.deliver("agent-1", ServerMessage.newBuilder()
                .setTaskAssigned(GatewayTestFixtures.assignment("agent-1", "assignment-1")).build());

        assertThat(store.appended).singleElement().satisfies(command -> {
            assertThat(command.sequence()).isEqualTo(1);
            assertThat(command.message().getTaskAssigned().getMetadata().getSequence()).isEqualTo(1);
        });
        assertThat(outbound.messages).extracting(ServerMessage::getPayloadCase)
                .containsExactly(ServerMessage.PayloadCase.READY, ServerMessage.PayloadCase.TASK_ASSIGNED);
    }

    @Test
    void reconnectReplaysDurableUnacknowledgedCommand() {
        ConnectionRegistry registry = new ConnectionRegistry();
        GatewayTestFixtures.RecordingCommandStore store = new GatewayTestFixtures.RecordingCommandStore();
        CommandDeliveryService delivery = new CommandDeliveryService(registry, store, fixedClock());
        AgentChannelService service = service(registry, delivery, store);
        GatewayTestFixtures.RecordingObserver firstOutbound = new GatewayTestFixtures.RecordingObserver();
        StreamObserver<AgentMessage> first = service.connect(firstOutbound);
        first.onNext(GatewayTestFixtures.hello("agent-1"));
        delivery.deliver("agent-1", ServerMessage.newBuilder()
                .setTaskAssigned(GatewayTestFixtures.assignment("agent-1", "assignment-1")).build());
        store.replay.add(store.appended.getFirst());

        first.onCompleted();
        GatewayTestFixtures.RecordingObserver secondOutbound = new GatewayTestFixtures.RecordingObserver();
        StreamObserver<AgentMessage> second = service.connect(secondOutbound);
        second.onNext(GatewayTestFixtures.hello("agent-1"));

        assertThat(secondOutbound.messages).extracting(ServerMessage::getPayloadCase)
                .containsExactly(ServerMessage.PayloadCase.READY, ServerMessage.PayloadCase.TASK_ASSIGNED);
        assertThat(secondOutbound.messages.get(1).getTaskAssigned().getMetadata().getSequence()).isEqualTo(1);
    }

    @Test
    void rejectsZeroAndAheadAcknowledgementsWithoutAdvancingDurableCursor() {
        ConnectionRegistry registry = new ConnectionRegistry();
        GatewayTestFixtures.RecordingCommandStore store = new GatewayTestFixtures.RecordingCommandStore();
        CommandDeliveryService delivery = new CommandDeliveryService(registry, store, fixedClock());
        AgentChannelService service = service(registry, delivery, store);
        GatewayTestFixtures.RecordingObserver outbound = new GatewayTestFixtures.RecordingObserver();
        service.connect(outbound).onNext(GatewayTestFixtures.hello("agent-1"));
        AgentConnection connection = registry.current("agent-1").orElseThrow();

        assertThatThrownBy(() -> delivery.acknowledge(connection, 0))
                .isInstanceOf(InvalidAcknowledgementException.class);
        assertThatThrownBy(() -> delivery.acknowledge(connection, 1))
                .isInstanceOf(InvalidAcknowledgementException.class);
        assertThat(store.acknowledged).isEmpty();
    }

    @Test
    void acceptsActualDeliveryAndMakesDuplicateAckIdempotent() {
        ConnectionRegistry registry = new ConnectionRegistry();
        GatewayTestFixtures.RecordingCommandStore store = new GatewayTestFixtures.RecordingCommandStore();
        CommandDeliveryService delivery = new CommandDeliveryService(registry, store, fixedClock());
        AgentChannelService service = service(registry, delivery, store);
        GatewayTestFixtures.RecordingObserver outbound = new GatewayTestFixtures.RecordingObserver();
        service.connect(outbound).onNext(GatewayTestFixtures.hello("agent-1"));
        AgentConnection connection = registry.current("agent-1").orElseThrow();
        delivery.deliver("agent-1", ServerMessage.newBuilder()
                .setTaskAssigned(GatewayTestFixtures.assignment("agent-1", "assignment-ack")).build());

        delivery.acknowledge(connection, 1);
        delivery.acknowledge(connection, 1);

        assertThat(connection.lastAckSequence()).isEqualTo(1);
        assertThat(store.acknowledged).containsExactly(1L);
    }

    private static AgentChannelService service(ConnectionRegistry registry, CommandDeliveryService delivery,
            GatewayTestFixtures.RecordingCommandStore store) {
        GatewayTestFixtures.RecordingStateStore state = new GatewayTestFixtures.RecordingStateStore();
        GatewayTestFixtures.RecordingApplicationHandler app = new GatewayTestFixtures.RecordingApplicationHandler();
        InboundEventHandler inbound = new InboundEventHandler(registry, new GatewayTestFixtures.RecordingInboundStore(),
                app, delivery, fixedClock());
        return new AgentChannelService(GatewayTestFixtures.VERSION, registry, state, AgentAuthenticator.allowAll(),
                () -> "test-peer", delivery, inbound, fixedClock());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);
    }
}
