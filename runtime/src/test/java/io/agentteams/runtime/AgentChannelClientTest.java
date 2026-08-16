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

        clock.advance(Duration.ofSeconds(10));
        assertThat(client.heartbeat(taskId, "late")).isEqualTo(HeartbeatResult.EXPIRED);
        assertThat(client.heartbeat(UUID.randomUUID(), "missing"))
                .isEqualTo(HeartbeatResult.UNKNOWN_LEASE);
        assertThat(port.messages()).hasSize(2);
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
