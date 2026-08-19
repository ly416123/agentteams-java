package io.agentteams.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.agentteams.contracts.v1.AgentHello;
import io.agentteams.contracts.v1.AgentMessage;
import io.agentteams.contracts.v1.AgentReady;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.ProtocolVersion;
import io.agentteams.contracts.v1.TaskAssigned;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentChannelClientTest {
    private static final Instant START = Instant.parse("2026-08-16T00:00:00Z");

    @Test
    void sendsHelloOnlyWhenConnectingAndRejectsIllegalLifecycleTransitions() {
        RecordingPort port = new RecordingPort();
        AgentChannelClient client = client(port);

        assertThat(client.state()).isEqualTo(AgentChannelState.DISCONNECTED);
        client.connect(hello());
        assertThat(client.state()).isEqualTo(AgentChannelState.CONNECTING);
        assertThat(port.messages()).extracting(AgentMessage::getPayloadCase)
                .containsExactly(AgentMessage.PayloadCase.HELLO);
        assertThatThrownBy(() -> client.connect(hello()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONNECTING");

        client.onReady(ready(true));
        assertThat(client.state()).isEqualTo(AgentChannelState.READY);
        assertThatThrownBy(() -> client.onReady(ready(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("READY");
    }

    @Test
    void heartbeatRequiresReadyStateAndAValidUnexpiredLease() {
        MutableClock clock = new MutableClock(START);
        RecordingPort port = new RecordingPort();
        AgentChannelClient client = new AgentChannelClient("agent-1", port, clock, Duration.ofSeconds(5));
        UUID taskId = UUID.randomUUID();
        AgentLease lease = new AgentLease(taskId, "attempt-1", "lease-1", START.plusSeconds(10));
        client.registerLease(lease);

        assertThat(client.heartbeat(taskId, "running")).isEqualTo(HeartbeatResult.NOT_READY);
        client.connect(hello());
        client.onReady(ready(true));
        assertThat(client.heartbeat(taskId, "running")).isEqualTo(HeartbeatResult.SENT);
        assertThat(port.messages()).last().extracting(AgentMessage::getPayloadCase)
                .isEqualTo(AgentMessage.PayloadCase.TASK_HEARTBEAT);
        assertThat(port.messages().get(1).getTaskHeartbeat().getMetadata().getLeaseId())
                .isEqualTo("lease-1");
        assertThat(port.messages().get(1).getTaskHeartbeat().getLeaseExpiresAt().getSeconds())
                .isEqualTo(START.plusSeconds(40).getEpochSecond());

        clock.advance(Duration.ofSeconds(40));
        assertThat(client.heartbeat(taskId, "late")).isEqualTo(HeartbeatResult.EXPIRED);
        assertThat(client.heartbeat(UUID.randomUUID(), "missing"))
                .isEqualTo(HeartbeatResult.UNKNOWN_LEASE);
        assertThat(port.messages()).hasSize(2);
    }

    @Test
    void sendsAgentHeartbeatWhenReadyWithoutATaskLease() {
        MutableClock clock = new MutableClock(START);
        RecordingPort port = new RecordingPort();
        AgentChannelClient client = new AgentChannelClient("agent-1", port, clock, Duration.ofSeconds(5));

        assertThat(client.heartbeatAgent("idle")).isFalse();
        client.connect(hello());
        client.onReady(ready(true));

        assertThat(client.heartbeatAgent("idle")).isTrue();
        AgentMessage heartbeat = port.messages().get(1);
        assertThat(heartbeat.getPayloadCase()).isEqualTo(AgentMessage.PayloadCase.AGENT_HEARTBEAT);
        assertThat(heartbeat.getAgentHeartbeat().getMetadata().getAgentId()).isEqualTo("agent-1");
        assertThat(heartbeat.getAgentHeartbeat().getMetadata().getTaskId()).isEmpty();
        assertThat(heartbeat.getAgentHeartbeat().getStatus()).isEqualTo("idle");
    }

    @Test
    void reconnectUsesBackoffPreservesLeaseAndStopsAfterServerRejectionOrClose() {
        MutableClock clock = new MutableClock(START);
        RecordingPort port = new RecordingPort();
        AgentChannelClient client = new AgentChannelClient("agent-1", port, clock, Duration.ofSeconds(5));
        UUID taskId = UUID.randomUUID();
        client.registerLease(new AgentLease(taskId, "attempt-1", "lease-1", START.plusSeconds(30)));
        client.connect(hello());
        client.onReady(ready(true));

        client.onDisconnected();
        assertThat(client.state()).isEqualTo(AgentChannelState.RECONNECTING);
        assertThat(client.reconnectIfDue(hello())).isFalse();
        clock.advance(Duration.ofSeconds(5));
        assertThat(client.reconnectIfDue(hello())).isTrue();
        assertThat(client.state()).isEqualTo(AgentChannelState.CONNECTING);
        client.onReady(ready(true));
        assertThat(client.heartbeat(taskId, "reconnected")).isEqualTo(HeartbeatResult.SENT);
        assertThat(port.messages()).extracting(AgentMessage::getPayloadCase)
                .containsExactly(AgentMessage.PayloadCase.HELLO, AgentMessage.PayloadCase.HELLO,
                        AgentMessage.PayloadCase.TASK_HEARTBEAT);

        client.onDisconnected();
        clock.advance(Duration.ofSeconds(5));
        assertThat(client.reconnectIfDue(rejectedHello())).isTrue();
        client.onReady(ready(false));
        assertThat(client.state()).isEqualTo(AgentChannelState.REJECTED);
        assertThat(client.reconnectIfDue(hello())).isFalse();

        client.close();
        assertThat(client.state()).isEqualTo(AgentChannelState.CLOSED);
        client.onDisconnected();
        assertThat(client.state()).isEqualTo(AgentChannelState.CLOSED);
        assertThatThrownBy(() -> client.connect(hello()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOSED");
    }

    @Test
    void acceptsAssignmentThroughRuntimeAndRegistersLeaseForHeartbeats() {
        RecordingPort port = new RecordingPort();
        AgentChannelClient client = client(port);
        FakeRuntime runtime = new FakeRuntime();
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", port::send, runtime,
                Clock.fixed(START, ZoneOffset.UTC));
        runtime.start(new AgentRuntimeContext("qwenpaw", 1, Clock.fixed(START, ZoneOffset.UTC),
                result -> { }, java.util.Map.of()));
        client.connect(hello());
        client.onReady(ready(true));
        UUID taskId = UUID.randomUUID();

        RuntimeSubmission submission = client.onTaskAssigned(assignment(taskId), adapter);

        assertThat(submission.accepted()).isTrue();
        assertThat(client.heartbeat(taskId, "running")).isEqualTo(HeartbeatResult.SENT);
        assertThat(client.completeTask(RuntimeResult.success(taskId, "done", START), adapter))
                .isEqualTo(CompletionStatus.COMPLETED);
        assertThat(client.heartbeat(taskId, "after-completion"))
                .isEqualTo(HeartbeatResult.UNKNOWN_LEASE);
        assertThat(port.messages()).extracting(AgentMessage::getPayloadCase)
                .contains(AgentMessage.PayloadCase.TASK_ACCEPTED, AgentMessage.PayloadCase.TASK_HEARTBEAT,
                AgentMessage.PayloadCase.TASK_COMPLETED);
    }

    @Test
    void synchronizesLeaseVersionAfterRuntimeAdapterProgressBeforeHeartbeat() {
        MutableClock clock = new MutableClock(START);
        RecordingPort port = new RecordingPort();
        AgentChannelClient client = new AgentChannelClient("agent-1", port, clock, Duration.ofSeconds(5));
        FakeRuntime runtime = new FakeRuntime();
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", port::send, runtime, clock);
        runtime.start(new AgentRuntimeContext("qwenpaw", 1, clock, result -> { }, java.util.Map.of()));
        UUID taskId = UUID.randomUUID();
        client.connect(hello());
        client.onReady(ready(true));

        assertThat(client.onTaskAssigned(assignment(taskId), adapter).accepted()).isTrue();
        adapter.progress(taskId, 0, "running", "started");
        assertThat(client.advanceTaskEventVersion(taskId)).isTrue();
        assertThat(client.heartbeat(taskId, "running")).isEqualTo(HeartbeatResult.SENT);

        AgentMessage heartbeat = port.messages().get(port.messages().size() - 1);
        assertThat(heartbeat.getTaskHeartbeat().getMetadata().getExpectedVersion()).isEqualTo(2);
    }

    @Test
    void rejectsAssignmentBeforeReadyAndDoesNotRegisterItsLease() {
        AgentChannelClient client = client(new RecordingPort());
        UUID taskId = UUID.randomUUID();
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", message -> { }, new FakeRuntime(),
                Clock.fixed(START, ZoneOffset.UTC));

        assertThatThrownBy(() -> client.onTaskAssigned(assignment(taskId), adapter))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DISCONNECTED");
        assertThat(client.heartbeat(taskId, "running")).isEqualTo(HeartbeatResult.NOT_READY);
    }

    private static AgentChannelClient client(RecordingPort port) {
        return new AgentChannelClient("agent-1", port, Clock.fixed(START, ZoneOffset.UTC), Duration.ofSeconds(5));
    }

    private static AgentHello hello() {
        return AgentHello.newBuilder().setMetadata(metadata("agent-1"))
                .setProtocolVersion(ProtocolVersion.newBuilder().setMajor(1).setMinor(0))
                .setRuntimeName("test").setRuntimeVersion("1").setMaxConcurrentTasks(1)
                .build();
    }

    private static AgentHello rejectedHello() {
        return hello().toBuilder().setRuntimeVersion("reconnect").build();
    }

    private static AgentReady ready(boolean accepted) {
        return AgentReady.newBuilder().setMetadata(metadata("agent-1")).setAccepted(accepted)
                .setNegotiatedVersion(ProtocolVersion.newBuilder().setMajor(1).setMinor(0))
                .setRejectionReason(accepted ? "" : "unsupported")
                .build();
    }

    private static EventMetadata metadata(String agentId) {
        return EventMetadata.newBuilder().setEventId(UUID.randomUUID().toString())
                .setAgentId(agentId).setOccurredAt(Timestamp.getDefaultInstance()).build();
    }

    private static TaskAssigned assignment(UUID taskId) {
        return TaskAssigned.newBuilder().setMetadata(EventMetadata.newBuilder()
                .setEventId(UUID.randomUUID().toString()).setAgentId("agent-1").setTaskId(taskId.toString())
                .setAttemptId("attempt-1").setLeaseId("lease-1")
                .setOccurredAt(Timestamp.getDefaultInstance()).build())
                .setTaskType("chat").setInputJson(ByteString.copyFromUtf8("{}"))
                .setLeaseExpiresAt(Timestamp.newBuilder()
                        .setSeconds(START.plusSeconds(30).getEpochSecond()).build())
                .build();
    }

    private static final class RecordingPort implements AgentChannelPort {
        private final List<AgentMessage> messages = new ArrayList<>();

        @Override
        public void send(AgentMessage message) {
            messages.add(message);
        }

        List<AgentMessage> messages() {
            return messages;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
