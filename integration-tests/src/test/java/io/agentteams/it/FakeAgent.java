package io.agentteams.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentteams.contracts.v1.Ack;
import io.agentteams.contracts.v1.AgentChannelGrpc;
import io.agentteams.contracts.v1.AgentHello;
import io.agentteams.contracts.v1.AgentMessage;
import io.agentteams.contracts.v1.ArtifactRef;
import io.agentteams.contracts.v1.ConfigChanged;
import io.agentteams.contracts.v1.ConfigApplied;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.ProtocolVersion;
import io.agentteams.contracts.v1.ServerMessage;
import io.agentteams.contracts.v1.TaskAccepted;
import io.agentteams.contracts.v1.TaskAssigned;
import io.agentteams.contracts.v1.TaskCompleted;
import io.agentteams.contracts.v1.TaskHeartbeat;
import io.agentteams.contracts.v1.TaskProgress;
import io.agentteams.gateway.SequencedCommand;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Minimal real gRPC Agent used by the push/replay regression test. */
final class FakeAgent implements AutoCloseable {

    private static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(5);

    private final ManagedChannel channel;
    private final StreamObserver<AgentMessage> requests;
    private final String agentId;
    private final BlockingQueue<ServerMessage> responses = new LinkedBlockingQueue<>();
    private final CopyOnWriteArrayList<String> receivedKinds = new CopyOnWriteArrayList<>();
    private volatile Throwable failure;
    private volatile boolean requestsClosed;

    private FakeAgent(ManagedChannel channel, StreamObserver<AgentMessage> requests, String agentId) {
        this.channel = channel;
        this.requests = requests;
        this.agentId = agentId;
    }

    static FakeAgent connect(String host, int port, String agentId, ProtocolVersion version) {
        return connect(host, port, agentId, version, Map.of("tasks", "1"));
    }

    static FakeAgent connect(String host, int port, String agentId, ProtocolVersion version,
            Map<String, String> capabilities) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        FakeAgent[] holder = new FakeAgent[1];
        StreamObserver<AgentMessage> requests = AgentChannelGrpc.newStub(channel).connect(new StreamObserver<>() {
            @Override
            public void onNext(ServerMessage message) {
                holder[0].receivedKinds.add(message.getPayloadCase().name());
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
        requests.onNext(hello(agentId, version, capabilities));
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

    ConfigChanged awaitConfigChanged(long configVersion) throws InterruptedException {
        ServerMessage message = await(candidate -> candidate.hasConfigChanged()
                && candidate.getConfigChanged().getConfigVersion() == configVersion);
        return message.getConfigChanged();
    }

    void acknowledge(ConfigChanged changed) {
        acknowledgeSequence(changed.getMetadata().getSequence(), changed.getMetadata().getEventId());
    }

    void applyConfig(ConfigChanged changed) {
        requests.onNext(AgentMessage.newBuilder().setConfigApplied(ConfigApplied.newBuilder()
                .setMetadata(EventMetadata.newBuilder()
                        .setEventId(changed.getMetadata().getEventId())
                        .setAgentId(agentId)
                        .setOccurredAt(com.google.protobuf.Timestamp.newBuilder()
                                .setSeconds(System.currentTimeMillis() / 1000).build())
                        .build())
                .setConfigVersion(changed.getConfigVersion())
                .setApplied(true)
                .setBindingId(changed.getBindingId())
                .setSnapshotId(changed.getSnapshotId())
                .build()).build());
    }

    void accept(TaskAssigned assignment) {
        requests.onNext(AgentMessage.newBuilder().setTaskAccepted(TaskAccepted.newBuilder()
                .setMetadata(taskMetadata(assignment, UUID.randomUUID().toString(), 2))
                .setAccepted(true)
                .build()).build());
    }

    void progress(TaskAssigned assignment) {
        requests.onNext(AgentMessage.newBuilder().setTaskProgress(TaskProgress.newBuilder()
                .setMetadata(taskMetadata(assignment, UUID.randomUUID().toString(), 3))
                .setPercent(50)
                .setStatus("running")
                .setMessage("half way")
                .build()).build());
    }

    void heartbeat(TaskAssigned assignment) {
        requests.onNext(AgentMessage.newBuilder().setTaskHeartbeat(TaskHeartbeat.newBuilder()
                .setMetadata(taskMetadata(assignment, UUID.randomUUID().toString(), 4))
                .setStatus("running")
                .setLeaseExpiresAt(timestamp(Instant.now().plusSeconds(60)))
                .build()).build());
    }

    void complete(TaskAssigned assignment, String name, String storageKey, String sha256, long sizeBytes) {
        requests.onNext(AgentMessage.newBuilder().setTaskCompleted(TaskCompleted.newBuilder()
                .setMetadata(taskMetadata(assignment, UUID.randomUUID().toString(), 5))
                .setResultJson(com.google.protobuf.ByteString.copyFromUtf8("{\"ok\":true}"))
                .addArtifacts(ArtifactRef.newBuilder()
                        .setName(name)
                        .setUri(storageKey)
                        .setSha256(sha256)
                        .setSizeBytes(sizeBytes)
                        .build())
                .build()).build());
    }

    void completeWithEventId(TaskAssigned assignment, String eventId, String name, String storageKey,
            String sha256, long sizeBytes) {
        requests.onNext(AgentMessage.newBuilder().setTaskCompleted(TaskCompleted.newBuilder()
                .setMetadata(taskMetadata(assignment, eventId, 5))
                .setResultJson(com.google.protobuf.ByteString.copyFromUtf8("{\"ok\":true}"))
                .addArtifacts(ArtifactRef.newBuilder()
                        .setName(name)
                        .setUri(storageKey)
                        .setSha256(sha256)
                        .setSizeBytes(sizeBytes)
                        .build())
                .build()).build());
    }

    void acknowledge(SequencedCommand command) {
        TaskAssigned task = command.message().getTaskAssigned();
        acknowledgeSequence(command.sequence(), task.getMetadata().getEventId());
    }

    void acknowledge(TaskAssigned task) {
        acknowledgeSequence(task.getMetadata().getSequence(), task.getMetadata().getEventId());
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

    String failureDescription() {
        Throwable current = failure;
        return current == null ? "none" : current.toString();
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
            assertTrue(remaining > 0, "timed out waiting for a server message; received=" + receivedKinds);
            ServerMessage message = responses.poll(remaining, TimeUnit.NANOSECONDS);
            if (message != null && predicate.test(message)) {
                return message;
            }
        }
    }

    private static AgentMessage hello(String agentId, ProtocolVersion version,
            Map<String, String> capabilities) {
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
                .putAllCapabilities(capabilities)
                .setMaxConcurrentTasks(1)
                .build()).build();
    }

    private static EventMetadata taskMetadata(TaskAssigned assignment, String eventId, long expectedVersion) {
        return EventMetadata.newBuilder()
                .setEventId(eventId)
                .setAgentId(assignment.getMetadata().getAgentId())
                .setTaskId(assignment.getMetadata().getTaskId())
                .setAttemptId(assignment.getMetadata().getAttemptId())
                .setLeaseId(assignment.getMetadata().getLeaseId())
                .setExpectedVersion(expectedVersion)
                .setOccurredAt(timestamp(Instant.now()))
                .build();
    }

    private static com.google.protobuf.Timestamp timestamp(Instant instant) {
        return com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
