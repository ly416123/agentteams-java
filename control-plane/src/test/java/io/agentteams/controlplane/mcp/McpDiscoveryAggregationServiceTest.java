package io.agentteams.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class McpDiscoveryAggregationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
    private static final UUID SERVER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void oneFreshHealthyInstanceMakesServerAvailable() {
        InMemoryPort port = new InMemoryPort();
        port.record(observation(3, "pod-a", "sha256:aaa", true, "SUCCESS", NOW.minusSeconds(5),
                NOW.plusSeconds(30)));

        McpDiscoveryAggregate aggregate = service(port).aggregate(SERVER_ID, 3, NOW);

        assertThat(aggregate.status()).isEqualTo(McpDiscoveryStatus.AVAILABLE);
        assertThat(aggregate.toolsDigest()).isEqualTo("sha256:aaa");
        assertThat(aggregate.healthyInstances()).isEqualTo(1);
        assertThat(aggregate.freshInstances()).isEqualTo(1);
        assertThat(aggregate.latestObservedAt()).isEqualTo(NOW.minusSeconds(5));
    }

    @Test
    void allFreshInstancesFailedMakesServerUnavailable() {
        InMemoryPort port = new InMemoryPort();
        port.record(observation(3, "pod-a", "", false, "TIMEOUT", NOW.minusSeconds(5), NOW.plusSeconds(30)));
        port.record(observation(3, "pod-b", "", false, "DNS_FAILURE", NOW.minusSeconds(3), NOW.plusSeconds(30)));

        McpDiscoveryAggregate aggregate = service(port).aggregate(SERVER_ID, 3, NOW);

        assertThat(aggregate.status()).isEqualTo(McpDiscoveryStatus.UNAVAILABLE);
        assertThat(aggregate.healthyInstances()).isZero();
        assertThat(aggregate.freshInstances()).isEqualTo(2);
        assertThat(aggregate.failureCategories()).containsExactly("DNS_FAILURE", "TIMEOUT");
    }

    @Test
    void noFreshObservationMakesServerUnknown() {
        InMemoryPort port = new InMemoryPort();
        port.record(observation(3, "pod-a", "sha256:old", true, "SUCCESS", NOW.minusSeconds(120),
                NOW.minusSeconds(1)));

        McpDiscoveryAggregate aggregate = service(port).aggregate(SERVER_ID, 3, NOW);

        assertThat(aggregate.status()).isEqualTo(McpDiscoveryStatus.UNKNOWN);
        assertThat(aggregate.freshInstances()).isZero();
        assertThat(aggregate.healthyInstances()).isZero();
        assertThat(aggregate.latestObservedAt()).isNull();
    }

    @Test
    void oldServerRevisionIsNotIncludedInCurrentAggregate() {
        InMemoryPort port = new InMemoryPort();
        port.record(observation(2, "pod-a", "sha256:old", true, "SUCCESS", NOW, NOW.plusSeconds(30)));
        port.record(observation(3, "pod-b", "sha256:new", true, "SUCCESS", NOW, NOW.plusSeconds(30)));

        McpDiscoveryAggregate aggregate = service(port).aggregate(SERVER_ID, 3, NOW);

        assertThat(aggregate.status()).isEqualTo(McpDiscoveryStatus.AVAILABLE);
        assertThat(aggregate.freshInstances()).isEqualTo(1);
        assertThat(aggregate.toolsDigest()).isEqualTo("sha256:new");
    }

    @Test
    void observationRejectsUnsafeIdentityAndInvalidWindow() {
        assertThatThrownBy(() -> observation(0, "pod/secret", "", false, "TIMEOUT", NOW, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> observation(-1, "pod-a", "", false, "TIMEOUT", NOW, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> observation(0, "pod-a", "", false, "TIMEOUT", NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> observation(0, "pod-a", "", false, "secret/mcp", NOW, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static McpDiscoveryAggregationService service(InMemoryPort port) {
        return new McpDiscoveryAggregationService(port, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static McpDiscoveryObservation observation(long revision, String instance, String digest,
            boolean healthy, String category, Instant observedAt, Instant expiresAt) {
        return new McpDiscoveryObservation(SERVER_ID, revision, instance, digest, healthy, category,
                observedAt, expiresAt);
    }

    private static final class InMemoryPort implements McpDiscoveryObservationPort {
        private final List<McpDiscoveryObservation> observations = new ArrayList<>();

        @Override
        public void record(McpDiscoveryObservation observation) {
            observations.removeIf(existing -> existing.serverId().equals(observation.serverId())
                    && existing.serverRevision() == observation.serverRevision()
                    && existing.instanceId().equals(observation.instanceId()));
            observations.add(observation);
        }

        @Override
        public List<McpDiscoveryObservation> find(UUID serverId, long serverRevision) {
            return observations.stream()
                    .filter(observation -> observation.serverId().equals(serverId)
                            && observation.serverRevision() == serverRevision)
                    .toList();
        }
    }
}
