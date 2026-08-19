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
    private final Consumer<Throwable> disconnected;
    private StreamObserver<AgentMessage> outbound;
    private long streamGeneration;
    private boolean closed;

    public GrpcAgentChannelPort(ManagedChannel channel, Consumer<ServerMessage> inbound) {
        this(channel, inbound, ignored -> { });
    }

    public GrpcAgentChannelPort(ManagedChannel channel, Consumer<ServerMessage> inbound,
            Consumer<Throwable> disconnected) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.inbound = Objects.requireNonNull(inbound, "inbound");
        this.disconnected = Objects.requireNonNull(disconnected, "disconnected");
    }

    /** Opens one bidirectional stream. The same port can be reused after disconnect(). */
    public synchronized void connect() {
        if (closed) {
            throw new IllegalStateException("Agent channel is closed");
        }
        if (outbound != null) {
            throw new IllegalStateException("Agent channel is already connected");
        }
        long generation = ++streamGeneration;
        outbound = AgentChannelGrpc.newStub(channel).connect(new StreamObserver<>() {
            @Override
            public void onNext(ServerMessage value) {
                inbound.accept(value);
            }

            @Override
            public void onError(Throwable throwable) {
                boolean current;
                synchronized (GrpcAgentChannelPort.this) {
                    current = generation == streamGeneration;
                    if (current) {
                        outbound = null;
                    }
                }
                if (current) {
                    disconnected.accept(throwable);
                }
            }

            @Override
            public void onCompleted() {
                boolean current;
                synchronized (GrpcAgentChannelPort.this) {
                    current = generation == streamGeneration;
                    if (current) {
                        outbound = null;
                    }
                }
                if (current) {
                    disconnected.accept(new IllegalStateException("Agent Gateway stream completed"));
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
        disconnect();
    }

    /** Closes only the current stream while keeping this port reusable. */
    public synchronized void disconnect() {
        streamGeneration++;
        StreamObserver<AgentMessage> current = outbound;
        outbound = null;
        if (current != null) {
            current.onCompleted();
        }
    }
}
