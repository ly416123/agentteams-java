package io.agentteams.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentteams.contracts.v1.Ack;
import io.agentteams.contracts.v1.AgentChannelGrpc;
import io.agentteams.contracts.v1.AgentHello;
import io.agentteams.contracts.v1.AgentMessage;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.ProtocolVersion;
import io.agentteams.contracts.v1.ServerMessage;
import io.agentteams.contracts.v1.TaskAssigned;
import io.agentteams.gateway.SequencedCommand;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Minimal real gRPC Agent used by the push/replay regression test. */
final class FakeAgent implements AutoCloseable {

    private static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(5);

    private final ManagedChannel channel;
    private final StreamObserver<AgentMessage> requests;
    private final String agentId;
    private final BlockingQueue<ServerMessage> responses = new LinkedBlockingQueue<>();
    private volatile Throwable failure;
    private volatile boolean requestsClosed;

    private FakeAgent(ManagedChannel channel, StreamObserver<AgentMessage> requests, String agentId) {
        this.channel = channel;
        this.requests = requests;
        this.agentId = agentId;
    }

    static FakeAgent connect(String host, int port, String agentId, ProtocolVersion version) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        FakeAgent[] holder = new FakeAgent[1];
        StreamObserver<AgentMessage> requests = AgentChannelGrpc.newStub(channel).connect(new StreamObserver<>() {
            @Override
            public void onNext(ServerMessage message) {
                holder[0].responses.add(message);
            }

            @Override
            public void onError(Throwable throwable) {
                holder[0].failure = throwable;
            }

            @Override
            public void onCompleted() {
                // The server completes the response stream after the client closes its request stream.
            }
        });
        FakeAgent agent = new FakeAgent(channel, requests, agentId);
        holder[0] = agent;
        requests.onNext(hello(agentId, version));
        return agent;
    }

    void awaitReady() throws InterruptedException {
        ServerMessage ready = await(message -> message.hasReady());
        assertTrue(ready.getReady().getAccepted(), ready.getReady().getRejectionReason());
    }

    TaskAssigned awaitTaskAssigned(String taskId) throws InterruptedException {
        ServerMessage message = await(candidate -> candidate.hasTaskAssigned()
                && taskId.equals(candidate.getTaskAssigned().getMetadata().getTaskId()));
        return message.getTaskAssigned();
    }

    void acknowledge(SequencedCommand command) {
        TaskAssigned task = command.message().getTaskAssigned();
        acknowledgeSequence(command.sequence(), task.getMetadata().getEventId());
    }

    void acknowledgeSequence(long sequence, String commandEventId) {
        requests.onNext(AgentMessage.newBuilder().setAck(Ack.newBuilder()
                .setMetadata(EventMetadata.newBuilder()
                        .setEventId("ack-" + UUID.randomUUID())
                        .setAgentId(agentId)
                        .setOccurredAt(com.google.protobuf.Timestamp.newBuilder()
                                .setSeconds(System.currentTimeMillis() / 1000)
                                .build())
                        .build())
                .setAckedEventId(commandEventId)
                .setAckedSequence(sequence)
                .build()).build());
    }

    void closeWithoutAcknowledging() {
        if (!requestsClosed) {
            requestsClosed = true;
            requests.onCompleted();
        }
    }

    @Override
    public void close() throws InterruptedException {
        closeWithoutAcknowledging();
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private ServerMessage await(java.util.function.Predicate<ServerMessage> predicate) throws InterruptedException {
        long deadline = System.nanoTime() + MESSAGE_TIMEOUT.toNanos();
        while (true) {
            if (failure != null) {
                throw new AssertionError("fake agent gRPC stream failed", failure);
            }
            long remaining = deadline - System.nanoTime();
            assertTrue(remaining > 0, "timed out waiting for a server message");
            ServerMessage message = responses.poll(remaining, TimeUnit.NANOSECONDS);
            if (message != null && predicate.test(message)) {
                return message;
            }
        }
    }

    private static AgentMessage hello(String agentId, ProtocolVersion version) {
        return AgentMessage.newBuilder().setHello(AgentHello.newBuilder()
                .setMetadata(EventMetadata.newBuilder()
                        .setEventId("hello-" + UUID.randomUUID())
                        .setAgentId(agentId)
                        .setOccurredAt(com.google.protobuf.Timestamp.newBuilder()
                                .setSeconds(System.currentTimeMillis() / 1000)
                                .build())
                        .build())
                .setProtocolVersion(version)
                .setRuntimeName("fake-agent")
                .setRuntimeVersion("test")
                .putCapabilities("tasks", "1")
                .setMaxConcurrentTasks(1)
                .build()).build();
    }
}
