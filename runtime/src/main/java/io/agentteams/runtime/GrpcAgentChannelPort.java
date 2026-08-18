package io.agentteams.runtime;

import io.agentteams.contracts.v1.AgentChannelGrpc;
import io.agentteams.contracts.v1.AgentMessage;
import io.agentteams.contracts.v1.ServerMessage;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;

import java.util.Objects;
import java.util.function.Consumer;

/** gRPC transport adapter for the runtime-neutral AgentChannelPort. */
public final class GrpcAgentChannelPort implements AgentChannelPort, AutoCloseable {
    private final ManagedChannel channel;
    private final Consumer<ServerMessage> inbound;
    private StreamObserver<AgentMessage> outbound;
    private boolean closed;

    public GrpcAgentChannelPort(ManagedChannel channel, Consumer<ServerMessage> inbound) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.inbound = Objects.requireNonNull(inbound, "inbound");
    }

    /** Opens one bidirectional stream; reconnect creates a new port instance. */
    public synchronized void connect() {
        if (closed) {
            throw new IllegalStateException("Agent channel is closed");
        }
        if (outbound != null) {
            throw new IllegalStateException("Agent channel is already connected");
        }
        outbound = AgentChannelGrpc.newStub(channel).connect(new StreamObserver<>() {
            @Override
            public void onNext(ServerMessage value) {
                inbound.accept(value);
            }

            @Override
            public void onError(Throwable throwable) {
                synchronized (GrpcAgentChannelPort.this) {
                    outbound = null;
                }
            }

            @Override
            public void onCompleted() {
                synchronized (GrpcAgentChannelPort.this) {
                    outbound = null;
                }
            }
        });
    }

    @Override
    public synchronized void send(AgentMessage message) {
        Objects.requireNonNull(message, "message");
        if (closed || outbound == null) {
            throw new IllegalStateException("Agent channel is not connected");
        }
        outbound.onNext(message);
    }

    @Override
    public synchronized void close() {
        closed = true;
        StreamObserver<AgentMessage> current = outbound;
        outbound = null;
        if (current != null) {
            current.onCompleted();
        }
    }
}
