package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.contracts.v1.ServerMessage;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConnectionRegistryTest {

    @Test
    void staleConnectionCannotCloseReplacementAndRegistryHasNoTaskState() {
        ConnectionRegistry registry = new ConnectionRegistry();
        AgentConnection first = registry.open(sink(), "peer-1", Instant.parse("2026-08-16T00:00:00Z"));
        AgentConnection second = registry.open(sink(), "peer-2", Instant.parse("2026-08-16T00:01:00Z"));
        AgentProfile profile = new AgentProfile("agent-1", "qwenpaw", "0.4.0", Map.of("tasks", "1"));
        registry.register(first, profile, 3, Instant.parse("2026-08-16T00:00:00Z"));
        registry.register(second, profile, 3, Instant.parse("2026-08-16T00:01:00Z"));

        assertThat(registry.current("agent-1")).hasValue(second);
        registry.close(first);
        assertThat(registry.current("agent-1")).hasValue(second);
        ConnectionRegistry.ConnectionSnapshot snapshot = registry.snapshot(second).orElseThrow();
        assertThat(snapshot.connectionId()).isInstanceOf(UUID.class);
        assertThat(snapshot.agentId()).isEqualTo("agent-1");
        assertThat(snapshot.runtime()).isEqualTo("qwenpaw");
        assertThat(snapshot.capabilities()).containsEntry("tasks", "1");
        assertThat(snapshot.lastAckSequence()).isEqualTo(3);
        assertThat(Arrays.stream(ConnectionRegistry.ConnectionSnapshot.class.getRecordComponents())
                .map(component -> component.getName()))
                .doesNotContain("task", "taskId", "attemptId", "inputJson", "taskState");
    }

    @Test
    void replacingConnectionActivelyClosesOldStreamWithNonRetryableStaleReason() {
        List<AgentConnection> terminated = new ArrayList<>();
        List<ConnectionTermination.Termination> reasons = new ArrayList<>();
        AtomicInteger closed = new AtomicInteger();
        ConnectionRegistry registry = new ConnectionRegistry((connection, termination) -> {
            terminated.add(connection);
            reasons.add(termination);
        }, new GatewayMetricsPort() {
            @Override
            public void connectionOpened() {
            }

            @Override
            public void connectionClosed() {
                closed.incrementAndGet();
            }

            @Override
            public void connectionRegistered() {
            }

            @Override
            public void eventRejected() {
            }

            @Override
            public void commandAppended() {
            }

            @Override
            public void commandDeduplicated() {
            }
        });
        AgentConnection first = registry.open(sink(), "peer-1", Instant.parse("2026-08-16T00:00:00Z"));
        AgentConnection second = registry.open(sink(), "peer-2", Instant.parse("2026-08-16T00:01:00Z"));
        AgentProfile profile = new AgentProfile("agent-1", "qwenpaw", "0.4.0", Map.of());

        registry.register(first, profile, 0, Instant.parse("2026-08-16T00:00:00Z"));
        registry.register(second, profile, 0, Instant.parse("2026-08-16T00:01:00Z"));

        assertThat(terminated).containsExactly(first);
        assertThat(reasons).singleElement().satisfies(reason -> {
            assertThat(reason.code()).isEqualTo("STALE_CONNECTION");
            assertThat(reason.retryable()).isFalse();
        });
        assertThat(closed).hasValue(1);
        assertThat(registry.current("agent-1")).hasValue(second);
    }

    @Test
    void normalizesOptionalWorkerVersionFactsAndEnforcesLimit() {
        String maximum = "x".repeat(512);
        AgentProfile profile = new AgentProfile("agent-1", "qwenpaw", "0.4.0", Map.of(),
                "  " + maximum + "  ", null, "  secret-9  ");

        assertThat(profile.specDigest()).isEqualTo(maximum);
        assertThat(profile.configRevision()).isEmpty();
        assertThat(profile.secretGeneration()).isEqualTo("secret-9");
        assertThat(new ConnectionRegistry.ConnectionSnapshot(UUID.randomUUID(), "agent-1", "qwenpaw", "0.4.0",
                "  " + maximum + "  ", null, "secret-9", Map.of(), Instant.EPOCH, 0).specDigest())
                .isEqualTo(maximum);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AgentProfile("agent-1", "qwenpaw", "0.4.0",
                        Map.of(), "x".repeat(513), "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("specDigest");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ConnectionRegistry.ConnectionSnapshot(
                        UUID.randomUUID(), "agent-1", "qwenpaw", "0.4.0", "", "x".repeat(513), "", Map.of(),
                        Instant.EPOCH, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configRevision");
    }

    private static StreamObserver<ServerMessage> sink() {
        return new StreamObserver<>() {
            @Override
            public void onNext(ServerMessage value) {
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onCompleted() {
            }
        };
    }
}
