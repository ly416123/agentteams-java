package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.contracts.v1.AgentMessage;
import io.agentteams.contracts.v1.ProtocolVersion;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentChannelServiceTest {

    private final ConnectionRegistry registry = new ConnectionRegistry();
    private final GatewayTestFixtures.RecordingStateStore stateStore = new GatewayTestFixtures.RecordingStateStore();
    private final GatewayTestFixtures.RecordingCommandStore commandStore = new GatewayTestFixtures.RecordingCommandStore();
    private final GatewayTestFixtures.RecordingInboundStore inboundStore = new GatewayTestFixtures.RecordingInboundStore();
    private final GatewayTestFixtures.RecordingApplicationHandler application =
            new GatewayTestFixtures.RecordingApplicationHandler();
    private AgentChannelService service;

    @BeforeEach
    void setUp() {
        CommandDeliveryService delivery = new CommandDeliveryService(registry, commandStore, fixedClock());
        InboundEventHandler inbound = new InboundEventHandler(registry, inboundStore, application, delivery, fixedClock());
        service = new AgentChannelService(GatewayTestFixtures.VERSION, registry, stateStore,
                AgentAuthenticator.allowAll(), () -> "test-peer", delivery, inbound, fixedClock());
    }

    @Test
    void registersOnlyAfterHelloAndSendsReady() {
        GatewayTestFixtures.RecordingObserver outbound = new GatewayTestFixtures.RecordingObserver();
        StreamObserver<AgentMessage> inbound = service.connect(outbound);

        inbound.onNext(GatewayTestFixtures.hello("agent-1"));

        assertThat(outbound.error).isNull();
        assertThat(outbound.messages).singleElement().satisfies(message -> {
            assertThat(message.hasReady()).isTrue();
            assertThat(message.getReady().getAccepted()).isTrue();
            assertThat(message.getReady().getNegotiatedVersion()).isEqualTo(GatewayTestFixtures.VERSION);
        });
        assertThat(registry.current("agent-1")).isPresent();
        assertThat(stateStore.registered).singleElement().extracting(ConnectionRegistry.ConnectionSnapshot::agentId)
                .isEqualTo("agent-1");
    }

    @Test
    void negotiatesPeerVersionAndReportsItInReady() {
        AtomicReference<ProtocolVersion> peerSeen = new AtomicReference<>();
        List<String> order = new ArrayList<>();
        ProtocolVersion peerVersion = ProtocolVersion.newBuilder().setMajor(2).setMinor(1).build();
        CommandDeliveryService delivery = new CommandDeliveryService(registry, commandStore, fixedClock());
        InboundEventHandler inbound = new InboundEventHandler(registry, inboundStore, application, delivery, fixedClock());
        AgentChannelService negotiatedService = new AgentChannelService(GatewayTestFixtures.VERSION, registry,
                stateStore, (connection, hello) -> {
                    order.add("authenticate");
                    return AuthenticationPort.AuthenticationDecision.allow();
                }, () -> "test-peer", (local, peer) -> {
                    order.add("negotiate");
                    peerSeen.set(peer);
                    return peer;
                }, delivery, inbound, fixedClock());
        GatewayTestFixtures.RecordingObserver outbound = new GatewayTestFixtures.RecordingObserver();

        negotiatedService.connect(outbound).onNext(GatewayTestFixtures.hello("agent-1").toBuilder()
                .setHello(GatewayTestFixtures.hello("agent-1").getHello().toBuilder()
                        .setProtocolVersion(peerVersion)).build());

        assertThat(peerSeen).hasValue(peerVersion);
        assertThat(order).containsExactly("authenticate", "negotiate");
        assertThat(outbound.messages).singleElement().extracting(message -> message.getReady().getNegotiatedVersion())
                .isEqualTo(peerVersion);
        assertThat(registry.current("agent-1")).isPresent();
    }

    @Test
    void rejectsAuthenticationAndClosesStreamBeforeRegistration() {
        ConnectionRegistry rejectedRegistry = new ConnectionRegistry();
        GatewayTestFixtures.RecordingStateStore rejectedState = new GatewayTestFixtures.RecordingStateStore();
        GatewayTestFixtures.RecordingCommandStore rejectedCommands = new GatewayTestFixtures.RecordingCommandStore();
        CommandDeliveryService delivery = new CommandDeliveryService(rejectedRegistry, rejectedCommands, fixedClock());
        InboundEventHandler inbound = new InboundEventHandler(rejectedRegistry,
                new GatewayTestFixtures.RecordingInboundStore(), application, delivery, fixedClock());
        GatewayTestFixtures.RecordingObserver outbound = new GatewayTestFixtures.RecordingObserver();
        AtomicReference<Boolean> negotiated = new AtomicReference<>(false);
        AgentChannelService orderedRejectedService = new AgentChannelService(GatewayTestFixtures.VERSION,
                rejectedRegistry, rejectedState,
                (connection, hello) -> AuthenticationPort.AuthenticationDecision.rejected("denied"),
                () -> "test-peer", (local, peer) -> {
                    negotiated.set(true);
                    return peer;
                }, delivery, inbound, fixedClock());

        orderedRejectedService.connect(outbound).onNext(GatewayTestFixtures.hello("agent-1"));

        assertThat(rejectedRegistry.current("agent-1")).isEmpty();
        assertThat(rejectedState.registered).isEmpty();
        assertThat(outbound.messages).isEmpty();
        assertThat(outbound.error).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) outbound.error).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(outbound.completed).isFalse();
        assertThat(negotiated).hasValue(false);
    }

    @Test
    void rejectsPayloadBeforeHelloWithoutRegisteringAgent() {
        GatewayTestFixtures.RecordingObserver outbound = new GatewayTestFixtures.RecordingObserver();
        StreamObserver<AgentMessage> inbound = service.connect(outbound);

        inbound.onNext(GatewayTestFixtures.accepted("agent-1", "accepted-1"));

        assertThat(registry.current("agent-1")).isEmpty();
        assertThat(outbound.error).isNotNull();
        assertThat(application.accepted).isEmpty();
    }

    @Test
    void rejectsIncompatibleHelloBeforeRegisteringAgent() {
        GatewayTestFixtures.RecordingObserver outbound = new GatewayTestFixtures.RecordingObserver();
        StreamObserver<AgentMessage> inbound = service.connect(outbound);
        AgentMessage incompatible = GatewayTestFixtures.hello("agent-1").toBuilder()
                .setHello(GatewayTestFixtures.hello("agent-1").getHello().toBuilder()
                        .setProtocolVersion(ProtocolVersion.newBuilder().setMajor(1).setMinor(0))).build();

        inbound.onNext(incompatible);

        assertThat(registry.current("agent-1")).isEmpty();
        assertThat(stateStore.registered).isEmpty();
        assertThat(outbound.error).isNotNull();
    }

    @Test
    void staleConnectionCannotForwardEventsAfterReconnect() {
        GatewayTestFixtures.RecordingObserver firstOutbound = new GatewayTestFixtures.RecordingObserver();
        StreamObserver<AgentMessage> first = service.connect(firstOutbound);
        first.onNext(GatewayTestFixtures.hello("agent-1"));

        GatewayTestFixtures.RecordingObserver secondOutbound = new GatewayTestFixtures.RecordingObserver();
        StreamObserver<AgentMessage> second = service.connect(secondOutbound);
        second.onNext(GatewayTestFixtures.hello("agent-1"));
        first.onNext(GatewayTestFixtures.accepted("agent-1", "stale-event"));
        second.onNext(GatewayTestFixtures.accepted("agent-1", "current-event"));

        assertThat(application.accepted).extracting(event -> event.getMetadata().getEventId())
                .containsExactly("current-event");
        assertThat(firstOutbound.error).isNotNull();
    }

    @Test
    void staleConnectionOnAnotherGatewayReplicaCannotRefreshOrForward() {
        SharedConnectionState sharedState = new SharedConnectionState();
        GatewayTestFixtures.RecordingApplicationHandler sharedApplication =
                new GatewayTestFixtures.RecordingApplicationHandler();
        ConnectionRegistry firstRegistry = new ConnectionRegistry();
        ConnectionRegistry secondRegistry = new ConnectionRegistry();
        AgentChannelService firstService = serviceFor(firstRegistry, sharedState, sharedApplication);
        AgentChannelService secondService = serviceFor(secondRegistry, sharedState, sharedApplication);

        GatewayTestFixtures.RecordingObserver firstOutbound = new GatewayTestFixtures.RecordingObserver();
        StreamObserver<AgentMessage> first = firstService.connect(firstOutbound);
        first.onNext(GatewayTestFixtures.hello("agent-1"));

        GatewayTestFixtures.RecordingObserver secondOutbound = new GatewayTestFixtures.RecordingObserver();
        StreamObserver<AgentMessage> second = secondService.connect(secondOutbound);
        second.onNext(GatewayTestFixtures.hello("agent-1"));

        first.onNext(GatewayTestFixtures.accepted("agent-1", "old-replica-event"));
        second.onNext(GatewayTestFixtures.accepted("agent-1", "new-replica-event"));

        assertThat(firstOutbound.error).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) firstOutbound.error).getStatus().getCode())
                .isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(sharedApplication.accepted).extracting(event -> event.getMetadata().getEventId())
                .containsExactly("new-replica-event");
    }

    private static AgentChannelService serviceFor(ConnectionRegistry registry, AgentStatePort state,
            GatewayApplicationHandler application) {
        CommandDeliveryService delivery = new CommandDeliveryService(registry,
                new GatewayTestFixtures.RecordingCommandStore(), fixedClock());
        InboundEventHandler inbound = new InboundEventHandler(registry,
                new GatewayTestFixtures.RecordingInboundStore(), application, delivery, fixedClock());
        return new AgentChannelService(GatewayTestFixtures.VERSION, registry, state,
                AgentAuthenticator.allowAll(), () -> "test-peer", delivery, inbound, fixedClock());
    }

    private static final class SharedConnectionState implements AgentStatePort {
        private final java.util.concurrent.ConcurrentHashMap<String, java.util.UUID> owners =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void registered(ConnectionRegistry.ConnectionSnapshot connection, Instant at) {
            owners.put(connection.agentId(), connection.connectionId());
        }

        @Override
        public boolean seen(ConnectionRegistry.ConnectionSnapshot connection, Instant at) {
            return connection.connectionId().equals(owners.get(connection.agentId()));
        }

        @Override
        public boolean disconnected(ConnectionRegistry.ConnectionSnapshot connection, Instant at) {
            return owners.remove(connection.agentId(), connection.connectionId());
        }
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);
    }
}
