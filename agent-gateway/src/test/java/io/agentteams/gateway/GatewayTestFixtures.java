package io.agentteams.gateway;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.agentteams.contracts.v1.AgentHello;
import io.agentteams.contracts.v1.AgentMessage;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.ProtocolVersion;
import io.agentteams.contracts.v1.ServerMessage;
import io.agentteams.contracts.v1.TaskAssigned;
import io.agentteams.contracts.v1.TaskCompleted;
import io.agentteams.contracts.v1.TaskFailed;
import io.agentteams.contracts.v1.TaskHeartbeat;
import io.agentteams.contracts.v1.TaskProgress;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class GatewayTestFixtures {

    static final ProtocolVersion VERSION = ProtocolVersion.newBuilder().setMajor(2).setMinor(3).build();

    private GatewayTestFixtures() {
    }

    static AgentMessage hello(String agentId) {
        return AgentMessage.newBuilder().setHello(AgentHello.newBuilder()
                .setMetadata(metadata("hello-" + agentId, agentId))
                .setProtocolVersion(VERSION)
                .setRuntimeName("qwenpaw")
                .setRuntimeVersion("0.4.0")
                .putCapabilities("tasks", "1")
                .setMaxConcurrentTasks(4)
                .build()).build();
    }

    static AgentMessage accepted(String agentId, String eventId) {
        return AgentMessage.newBuilder().setTaskAccepted(io.agentteams.contracts.v1.TaskAccepted.newBuilder()
                .setMetadata(taskMetadata(agentId, eventId))
                .setAccepted(true)
                .build()).build();
    }

    static AgentMessage progress(String agentId, String eventId) {
        return AgentMessage.newBuilder().setTaskProgress(TaskProgress.newBuilder()
                .setMetadata(taskMetadata(agentId, eventId))
                .setPercent(50)
                .setStatus("running")
                .setMessage("halfway")
                .build()).build();
    }

    static AgentMessage heartbeat(String agentId, String eventId) {
        return AgentMessage.newBuilder().setTaskHeartbeat(TaskHeartbeat.newBuilder()
                .setMetadata(taskMetadata(agentId, eventId))
                .setStatus("running")
                .setLeaseExpiresAt(Timestamp.newBuilder().setSeconds(1_800_000_030L).build())
                .build()).build();
    }

    static AgentMessage completed(String agentId, String eventId) {
        return AgentMessage.newBuilder().setTaskCompleted(TaskCompleted.newBuilder()
                .setMetadata(taskMetadata(agentId, eventId))
                .setResultJson(ByteString.copyFromUtf8("{\"ok\":true}"))
                .build()).build();
    }

    static AgentMessage failed(String agentId, String eventId) {
        return AgentMessage.newBuilder().setTaskFailed(TaskFailed.newBuilder()
                .setMetadata(taskMetadata(agentId, eventId))
                .setCode("RUNTIME_ERROR")
                .setMessage("worker failed")
                .setRetryable(true)
                .build()).build();
    }

    static AgentMessage ack(String agentId, String eventId, long sequence) {
        return AgentMessage.newBuilder().setAck(io.agentteams.contracts.v1.Ack.newBuilder()
                .setMetadata(metadata(eventId, agentId))
                .setAckedEventId("command-" + sequence)
                .setAckedSequence(sequence)
                .build()).build();
    }

    static TaskAssigned assignment(String agentId, String eventId) {
        return TaskAssigned.newBuilder()
                .setMetadata(taskMetadata(agentId, eventId))
                .setTaskType("summarize")
                .setInputJson(ByteString.copyFromUtf8("{\"text\":\"hello\"}"))
                .setLeaseExpiresAt(Timestamp.newBuilder().setSeconds(1_800_000_000L).build())
                .build();
    }

    static EventMetadata taskMetadata(String agentId, String eventId) {
        return metadata(eventId, agentId).toBuilder().setTaskId("task-1").setAttemptId("attempt-1").build();
    }

    static EventMetadata metadata(String eventId, String agentId) {
        return EventMetadata.newBuilder()
                .setEventId(eventId)
                .setAgentId(agentId)
                .setOccurredAt(Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
                .build();
    }

    static final class RecordingObserver implements StreamObserver<ServerMessage> {
        final List<ServerMessage> messages = new ArrayList<>();
        Throwable error;
        boolean completed;

        @Override
        public void onNext(ServerMessage message) {
            messages.add(message);
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }

    static final class RecordingStateStore implements GatewayStateStore {
        final List<AgentProfile> registered = new ArrayList<>();
        final List<ConnectionRegistry.ConnectionSnapshot> disconnected = new ArrayList<>();
        int seen;

        @Override
        public void registered(AgentProfile profile, Instant at) {
            registered.add(profile);
        }

        @Override
        public void seen(ConnectionRegistry.ConnectionSnapshot connection, Instant at) {
            seen++;
        }

        @Override
        public void disconnected(ConnectionRegistry.ConnectionSnapshot connection, Instant at) {
            disconnected.add(connection);
        }
    }

    static final class RecordingCommandStore implements CommandEventStore {
        final List<SequencedCommand> appended = new ArrayList<>();
        final List<SequencedCommand> replay = new ArrayList<>();
        final List<Long> acknowledged = new ArrayList<>();
        final Map<String, Map<UUID, Set<Long>>> delivered = new HashMap<>();
        long nextSequence = 1;

        @Override
        public SequencedCommand append(String agentId, ServerMessage command) {
            ServerMessage sequenced = command.toBuilder().setTaskAssigned(command.getTaskAssigned().toBuilder()
                    .setMetadata(command.getTaskAssigned().getMetadata().toBuilder()
                            .setAgentId(agentId).setSequence(nextSequence).build()).build()).build();
            SequencedCommand result = new SequencedCommand(nextSequence++, sequenced);
            appended.add(result);
            return result;
        }

        @Override
        public List<SequencedCommand> replayUnacknowledged(String agentId) {
            return List.copyOf(replay);
        }

        @Override
        public void markDelivered(String agentId, UUID connectionId, long sequence) {
            delivered.computeIfAbsent(agentId, ignored -> new HashMap<>())
                    .computeIfAbsent(connectionId, ignored -> new HashSet<>()).add(sequence);
        }

        @Override
        public AcknowledgementValidation validateAcknowledgement(String agentId, UUID connectionId, long sequence) {
            Set<Long> sequences = delivered.getOrDefault(agentId, Map.of()).getOrDefault(connectionId, Set.of());
            long highest = sequences.stream().mapToLong(Long::longValue).max().orElse(0);
            if (sequences.contains(sequence) && sequence <= highest) {
                return AcknowledgementValidation.accepted(highest);
            }
            return AcknowledgementValidation.rejected(highest, "sequence was not durably delivered to this connection");
        }

        @Override
        public void acknowledge(String agentId, long sequence) {
            acknowledged.add(sequence);
        }
    }

    static final class RecordingInboundStore implements InboundEventStore {
        final Set<String> eventIds = new HashSet<>();
        final List<String> seen = new ArrayList<>();
        final List<String> agents = new ArrayList<>();
        final List<UUID> connections = new ArrayList<>();

        @Override
        public boolean recordIfNew(String eventId, String agentId, UUID connectionId, Instant receivedAt) {
            seen.add(eventId);
            agents.add(agentId);
            connections.add(connectionId);
            return eventIds.add(eventId);
        }
    }

    static final class RecordingApplicationHandler implements GatewayApplicationHandler {
        final List<io.agentteams.contracts.v1.TaskAccepted> accepted = new ArrayList<>();
        final List<io.agentteams.contracts.v1.TaskProgress> progress = new ArrayList<>();
        final List<io.agentteams.contracts.v1.TaskHeartbeat> heartbeats = new ArrayList<>();
        final List<io.agentteams.contracts.v1.TaskCompleted> completed = new ArrayList<>();
        final List<io.agentteams.contracts.v1.TaskFailed> failed = new ArrayList<>();

        @Override
        public void taskAccepted(ConnectionRegistry.ConnectionSnapshot connection,
                io.agentteams.contracts.v1.TaskAccepted event) {
            accepted.add(event);
        }

        @Override
        public void taskProgress(ConnectionRegistry.ConnectionSnapshot connection,
                io.agentteams.contracts.v1.TaskProgress event) {
            progress.add(event);
        }

        @Override
        public void taskHeartbeat(ConnectionRegistry.ConnectionSnapshot connection,
                io.agentteams.contracts.v1.TaskHeartbeat event) {
            heartbeats.add(event);
        }

        @Override
        public void taskCompleted(ConnectionRegistry.ConnectionSnapshot connection,
                io.agentteams.contracts.v1.TaskCompleted event) {
            completed.add(event);
        }

        @Override
        public void taskFailed(ConnectionRegistry.ConnectionSnapshot connection,
                io.agentteams.contracts.v1.TaskFailed event) {
            failed.add(event);
        }
    }
}
