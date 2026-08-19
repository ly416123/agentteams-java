package io.agentteams.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.ProtocolVersion;
import io.agentteams.contracts.v1.ServerMessage;
import io.agentteams.contracts.v1.TaskAssigned;
import io.agentteams.gateway.AcknowledgementValidation;
import io.agentteams.gateway.AgentChannelService;
import io.agentteams.gateway.AgentStatePort;
import io.agentteams.gateway.AuthenticationPort;
import io.agentteams.gateway.CommandDeliveryService;
import io.agentteams.gateway.CommandEventStore;
import io.agentteams.gateway.ConnectionRegistry;
import io.agentteams.gateway.GatewayApplicationHandler;
import io.agentteams.gateway.InboundEventHandler;
import io.agentteams.gateway.InboundEventPort;
import io.agentteams.gateway.SequencedCommand;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TaskPushE2ETest {

    private static final String AGENT_ID = "agent-1";
    private static final ProtocolVersion VERSION = ProtocolVersion.newBuilder()
            .setMajor(2)
            .setMinor(3)
            .build();

    @Test
    void pushesTaskAndReplaysOnlyTheUnacknowledgedCommandAfterReconnect() throws Exception {
        ConnectionRegistry registry = new ConnectionRegistry();
        InMemoryCommandStore commands = new InMemoryCommandStore();
        CommandDeliveryService delivery = new CommandDeliveryService(registry, commands, Clock.systemUTC());
        AgentChannelService service = new AgentChannelService(
                VERSION,
                registry,
                new NoopStatePort(),
                (connection, hello) -> AuthenticationPort.AuthenticationDecision.allow(),
                () -> "test-peer",
                delivery,
                new InboundEventHandler(registry, new NoopInboundEvents(), new NoopApplication(), delivery,
                        Clock.systemUTC()),
                Clock.systemUTC());

        Server server = NettyServerBuilder.forPort(0).addService(service).build().start();
        try {
            FakeAgent firstConnection = FakeAgent.connect("localhost", server.getPort(), AGENT_ID, VERSION);
            try {
                firstConnection.awaitReady();
                SequencedCommand acknowledged = delivery.deliver(AGENT_ID, assignment("task-ack"));
                assertEquals(acknowledged.sequence(), firstConnection.awaitTaskAssigned("task-ack").getMetadata()
                        .getSequence());
                firstConnection.acknowledge(acknowledged);
                commands.awaitAcknowledged(acknowledged.sequence());

                SequencedCommand pending = delivery.deliver(AGENT_ID, assignment("task-replay"));
                assertEquals(pending.sequence(), firstConnection.awaitTaskAssigned("task-replay").getMetadata()
                        .getSequence());
                firstConnection.closeWithoutAcknowledging();
            } finally {
                firstConnection.close();
            }

            FakeAgent reconnect = FakeAgent.connect("localhost", server.getPort(), AGENT_ID, VERSION);
            try {
                reconnect.awaitReady();
                TaskAssigned replayed = reconnect.awaitTaskAssigned("task-replay");
                assertEquals(2L, replayed.getMetadata().getSequence());
                assertNotNull(replayed);
                reconnect.acknowledgeSequence(replayed.getMetadata().getSequence(), replayed.getMetadata().getEventId());
            } finally {
                reconnect.close();
            }
        } finally {
            server.shutdownNow();
            server.awaitTermination();
        }
    }

    private static ServerMessage assignment(String taskId) {
        EventMetadata metadata = EventMetadata.newBuilder()
                .setEventId("command-" + taskId)
                .setAgentId(AGENT_ID)
                .setTaskId(taskId)
                .setAttemptId("attempt-" + taskId)
                .setOccurredAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
                .build();
        return ServerMessage.newBuilder().setTaskAssigned(TaskAssigned.newBuilder()
                .setMetadata(metadata)
                .setTaskType("summarize")
                .setInputJson(com.google.protobuf.ByteString.copyFromUtf8("{\"text\":\"hello\"}"))
                .build()).build();
    }

    private static final class InMemoryCommandStore implements CommandEventStore {
        private final List<SequencedCommand> commands = new ArrayList<>();
        private final Map<UUID, List<Long>> delivered = new HashMap<>();
        private long nextSequence = 1;
        private long acknowledged;

        @Override
        public synchronized SequencedCommand append(String agentId, ServerMessage command) {
            long sequence = nextSequence++;
            ServerMessage sequenced = command.toBuilder().setTaskAssigned(command.getTaskAssigned().toBuilder()
                    .setMetadata(command.getTaskAssigned().getMetadata().toBuilder().setSequence(sequence).build())
                    .build()).build();
            SequencedCommand result = new SequencedCommand(sequence, sequenced);
            commands.add(result);
            return result;
        }

        @Override
        public synchronized List<SequencedCommand> replayUnacknowledged(String agentId) {
            return commands.stream().filter(command -> command.sequence() > acknowledged).toList();
        }

        @Override
        public synchronized void markDelivered(String agentId, UUID connectionId, long sequence) {
            delivered.computeIfAbsent(connectionId, ignored -> new ArrayList<>()).add(sequence);
        }

        @Override
        public synchronized AcknowledgementValidation validateAcknowledgement(String agentId, UUID connectionId,
                long sequence) {
            List<Long> sequences = delivered.getOrDefault(connectionId, List.of());
            return sequences.contains(sequence)
                    ? AcknowledgementValidation.accepted(sequence)
                    : AcknowledgementValidation.rejected(acknowledged, "command was not delivered");
        }

        @Override
        public synchronized void acknowledge(String agentId, long sequence) {
            acknowledged = Math.max(acknowledged, sequence);
            notifyAll();
        }

        @Override
        public synchronized long lastAcknowledgedSequence(String agentId) {
            return acknowledged;
        }

        synchronized void awaitAcknowledged(long sequence) throws InterruptedException {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (acknowledged < sequence) {
                long remaining = deadline - System.nanoTime();
                assertTrue(remaining > 0, "timed out waiting for command acknowledgement");
                wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
        }
    }

    private static final class NoopStatePort implements AgentStatePort {
        @Override
        public void registered(ConnectionRegistry.ConnectionSnapshot connection, Instant at) {
        }

        @Override
        public boolean seen(ConnectionRegistry.ConnectionSnapshot connection, Instant at) {
            return true;
        }

        @Override
        public boolean disconnected(ConnectionRegistry.ConnectionSnapshot connection, Instant at) {
            return true;
        }
    }

    private static final class NoopInboundEvents implements InboundEventPort {
        @Override
        public boolean recordIfNew(String eventId, String agentId, UUID connectionId, Instant receivedAt) {
            return true;
        }
    }

    private static final class NoopApplication implements GatewayApplicationHandler {
        @Override
        public void taskAccepted(ConnectionRegistry.ConnectionSnapshot connection,
                io.agentteams.contracts.v1.TaskAccepted event) {
        }

        @Override
        public void taskProgress(ConnectionRegistry.ConnectionSnapshot connection,
                io.agentteams.contracts.v1.TaskProgress event) {
        }

        @Override
        public void taskHeartbeat(ConnectionRegistry.ConnectionSnapshot connection,
                io.agentteams.contracts.v1.TaskHeartbeat event) {
        }

        @Override
        public void taskCompleted(ConnectionRegistry.ConnectionSnapshot connection,
                io.agentteams.contracts.v1.TaskCompleted event) {
        }

        @Override
        public void taskFailed(ConnectionRegistry.ConnectionSnapshot connection,
                io.agentteams.contracts.v1.TaskFailed event) {
        }
    }
}
