package io.agentteams.controlplane.mcp;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Computes a revision-fenced status from durable, instance-scoped discovery observations. */
@Service
public final class McpDiscoveryAggregationService {
    private final McpDiscoveryObservationPort observations;
    private final Clock clock;

    public McpDiscoveryAggregationService(McpDiscoveryObservationPort observations, Clock clock) {
        this.observations = Objects.requireNonNull(observations, "observations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public McpDiscoveryAggregate aggregate(UUID serverId, long serverRevision) {
        return aggregate(serverId, serverRevision, clock.instant());
    }

    public McpDiscoveryAggregate aggregate(UUID serverId, long serverRevision, Instant now) {
        Objects.requireNonNull(serverId, "serverId");
        if (serverRevision < 0) throw new IllegalArgumentException("serverRevision must not be negative");
        Objects.requireNonNull(now, "now");
        List<McpDiscoveryObservation> fresh = observations.find(serverId, serverRevision).stream()
                .filter(observation -> observation.expiresAt().isAfter(now))
                .sorted(Comparator.comparing(McpDiscoveryObservation::instanceId))
                .toList();
        if (fresh.isEmpty()) {
            return new McpDiscoveryAggregate(serverId, serverRevision, McpDiscoveryStatus.UNKNOWN,
                    "", 0, 0, null, List.of());
        }

        int healthy = (int) fresh.stream().filter(McpDiscoveryObservation::healthy).count();
        String digest = commonHealthyDigest(fresh);
        Instant latest = fresh.stream().map(McpDiscoveryObservation::observedAt).max(Comparator.naturalOrder()).orElseThrow();
        List<String> categories = new TreeSet<>(fresh.stream()
                .filter(observation -> !observation.healthy())
                .map(McpDiscoveryObservation::failureCategory)
                .toList()).stream().toList();
        McpDiscoveryStatus status = healthy > 0 ? McpDiscoveryStatus.AVAILABLE : McpDiscoveryStatus.UNAVAILABLE;
        return new McpDiscoveryAggregate(serverId, serverRevision, status, digest, healthy, fresh.size(),
                latest, categories);
    }

    private static String commonHealthyDigest(List<McpDiscoveryObservation> observations) {
        TreeSet<String> digests = new TreeSet<>(observations.stream()
                .filter(McpDiscoveryObservation::healthy)
                .map(McpDiscoveryObservation::toolsDigest)
                .filter(value -> !value.isBlank())
                .toList());
        return digests.size() == 1 ? digests.first() : "";
    }
}
