package io.agentteams.gateway;

import io.agentteams.contracts.v1.AgentChannelGrpc;
import io.agentteams.contracts.v1.AgentHello;
import io.agentteams.contracts.v1.AgentMessage;
import io.agentteams.contracts.v1.AgentReady;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.ProtocolVersion;
import io.agentteams.contracts.v1.ServerMessage;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Bidirectional gRPC endpoint enforcing Hello-first registration and current-session semantics. */
public final class AgentChannelService extends AgentChannelGrpc.AgentChannelImplBase {

    private final ProtocolVersion localVersion;
    private final ConnectionRegistry registry;
    private final AgentStatePort state;
    private final AuthenticationPort authentication;
    private final Supplier<String> transportIdentity;
    private final ProtocolNegotiationPort negotiation;
    private final CommandDeliveryService delivery;
    private final InboundEventHandler inbound;
    private final Clock clock;

    public AgentChannelService(ProtocolVersion localVersion, ConnectionRegistry registry, AgentStatePort state,
            AuthenticationPort authentication, Supplier<String> transportIdentity,
            CommandDeliveryService delivery, InboundEventHandler inbound, Clock clock) {
        this(localVersion, registry, state, authentication, transportIdentity,
                ProtocolNegotiationPort.compatiblePeerVersion(), delivery, inbound, clock);
    }

    public AgentChannelService(ProtocolVersion localVersion, ConnectionRegistry registry, AgentStatePort state,
            AuthenticationPort authentication, Supplier<String> transportIdentity,
            ProtocolNegotiationPort negotiation, CommandDeliveryService delivery,
            InboundEventHandler inbound, Clock clock) {
        this.localVersion = Objects.requireNonNull(localVersion, "localVersion");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.state = Objects.requireNonNull(state, "state");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.transportIdentity = Objects.requireNonNull(transportIdentity, "transportIdentity");
        this.negotiation = Objects.requireNonNull(negotiation, "negotiation");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.inbound = Objects.requireNonNull(inbound, "inbound");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public StreamObserver<AgentMessage> connect(StreamObserver<ServerMessage> responseObserver) {
        Objects.requireNonNull(responseObserver, "responseObserver");
        String peer = transportIdentity.get();
        if (peer == null || peer.isBlank()) {
            peer = "unknown-peer";
        }
        AgentConnection connection = registry.open(responseObserver, peer, clock.instant());
        return new StreamObserver<>() {
            private boolean terminated;

            @Override
            public void onNext(AgentMessage message) {
                if (terminated) {
                    return;
                }
                try {
                    receive(connection, message);
                } catch (RuntimeException error) {
                    terminated = true;
                    terminate(connection, responseObserver, error);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                if (!terminated) {
                    terminated = true;
                    disconnect(connection);
                }
            }

            @Override
            public void onCompleted() {
                if (!terminated) {
                    terminated = true;
                    disconnect(connection);
                    responseObserver.onCompleted();
                }
            }
        };
    }

    private void receive(AgentConnection connection, AgentMessage message) {
        if (message == null) {
            throw new GatewayExceptions.InvalidMessage("agent message is required");
        }
        if (connection.profile().isEmpty()) {
            if (!message.hasHello()) {
                throw new GatewayExceptions.InvalidMessage("first agent message must be Hello");
            }
            registerAfterHello(connection, message.getHello());
            return;
        }
        if (message.hasHello()) {
            throw new GatewayExceptions.InvalidMessage("Hello is allowed only as the first message");
        }
        ConnectionRegistry.ConnectionSnapshot snapshot = registry.snapshot(connection)
                .orElseThrow(() -> new GatewayExceptions.StaleConnection("connection is not registered"));
        if (!state.seen(snapshot, clock.instant())) {
            throw new GatewayExceptions.StaleConnection("connection is no longer current");
        }
        inbound.handle(connection, message);
    }

    private void registerAfterHello(AgentConnection connection, AgentHello hello) {
        validateHello(hello);
        AuthenticationPort.AuthenticationDecision decision = authentication.authenticate(connection, hello);
        if (decision == null || !decision.accepted()) {
            String reason = decision == null ? "authentication returned no decision" : decision.rejectionReason();
            throw new GatewayExceptions.AuthenticationRejected(reason);
        }
        ProtocolVersion negotiated;
        try {
            negotiated = negotiation.negotiate(localVersion, hello.getProtocolVersion());
        } catch (ProtocolNegotiationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new GatewayExceptions.ProtocolRejected(error.getMessage());
        }
        if (negotiated == null) {
            throw new GatewayExceptions.ProtocolRejected("negotiation returned no version");
        }
        Instant now = clock.instant();
        AgentProfile profile = new AgentProfile(hello.getMetadata().getAgentId(), hello.getRuntimeName(),
                hello.getRuntimeVersion(), hello.getCapabilitiesMap());
        registry.register(connection, profile, delivery.lastAcknowledgedSequence(profile.agentId()), now)
                .ifPresent(replaced -> state.disconnected(replaced, now));
        state.registered(registry.snapshot(connection).orElseThrow(), now);
        connection.outbound().onNext(ready(profile.agentId(), negotiated, now));
        delivery.replay(connection);
    }

    private void validateHello(AgentHello hello) {
        if (hello == null || !hello.hasMetadata()) {
            throw new GatewayExceptions.InvalidMessage("Hello metadata is required");
        }
        EventMetadata metadata = hello.getMetadata();
        if (metadata.getEventId().isBlank()) {
            throw new GatewayExceptions.InvalidMessage("Hello event_id is required");
        }
        if (metadata.getAgentId().isBlank()) {
            throw new GatewayExceptions.InvalidMessage("Hello agent_id is required");
        }
        if (hello.getRuntimeName().isBlank() || hello.getRuntimeVersion().isBlank()) {
            throw new GatewayExceptions.InvalidMessage("Hello runtime name and version are required");
        }
        if (!hello.hasProtocolVersion() || hello.getProtocolVersion().getMajor() == 0) {
            throw new GatewayExceptions.InvalidMessage("Hello protocol version is required");
        }
    }

    private static ServerMessage ready(String agentId, ProtocolVersion negotiated, Instant at) {
        EventMetadata metadata = EventMetadata.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setAgentId(agentId)
                .setOccurredAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(at.getEpochSecond())
                        .setNanos(at.getNano()).build())
                .build();
        return ServerMessage.newBuilder().setReady(AgentReady.newBuilder()
                .setMetadata(metadata)
                .setAccepted(true)
                .setNegotiatedVersion(negotiated)
                .build()).build();
    }

    private void terminate(AgentConnection connection, StreamObserver<ServerMessage> response, RuntimeException error) {
        disconnect(connection);
        Status status = status(error);
        response.onError(status.withDescription(error.getMessage()).withCause(error).asRuntimeException());
    }

    private void disconnect(AgentConnection connection) {
        registry.close(connection).ifPresent(snapshot -> state.disconnected(snapshot, clock.instant()));
    }

    private static Status status(RuntimeException error) {
        if (error instanceof GatewayExceptions.AuthenticationRejected) {
            return Status.UNAUTHENTICATED;
        }
        if (error instanceof GatewayExceptions.StaleConnection) {
            return Status.FAILED_PRECONDITION;
        }
        if (error instanceof InvalidAcknowledgementException) {
            return Status.INVALID_ARGUMENT;
        }
        if (error instanceof ProtocolNegotiationException || error instanceof GatewayExceptions.ProtocolRejected) {
            return Status.FAILED_PRECONDITION;
        }
        return Status.INVALID_ARGUMENT;
    }
}
