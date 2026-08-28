package io.agentteams.controlplane.mcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Safe MCP liveness probe backed by the policy-first tools/list execution path.
 *
 * <p>The probe performs no tool invocation and exposes only UP/DOWN, a bounded category, and
 * elapsed time. It intentionally uses the execution service so connector selection, policy, cache,
 * timeout handling, and credential-blind targets remain shared with normal discovery.</p>
 */
@Service
public final class McpHealthProbeService {
    private final McpToolExecutionService executionService;
    private final McpServerService serverService;
    private final Clock clock;
    private final McpDiscoveryObservationPort observations;
    private final McpDiscoveryInstanceProperties instanceProperties;

    @Autowired
    public McpHealthProbeService(McpToolExecutionService executionService, McpServerService serverService,
            Clock clock, McpDiscoveryObservationPort observations,
            McpDiscoveryInstanceProperties instanceProperties) {
        this.executionService = Objects.requireNonNull(executionService, "executionService");
        this.serverService = serverService;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.instanceProperties = Objects.requireNonNull(instanceProperties, "instanceProperties");
        this.instanceProperties.validate();
    }

    /** Compatibility constructor for non-Spring callers. */
    public McpHealthProbeService(McpToolExecutionService executionService) {
        this(executionService, null, Clock.systemUTC(), new NoopObservationPort(),
                new McpDiscoveryInstanceProperties());
    }

    /** Compatibility constructor for focused tests that do not need durable observations. */
    public McpHealthProbeService(McpToolExecutionService executionService, Clock clock) {
        this(executionService, null, clock, new NoopObservationPort(),
                new McpDiscoveryInstanceProperties());
    }

    public McpHealthProbeResult probe(UUID serverId, Duration timeout) {
        Objects.requireNonNull(serverId, "serverId");
        long started = System.nanoTime();
        McpServerRecord server = null;
        try {
            server = serverService == null ? null : serverService.get(serverId);
            McpToolCallResult discovery = executionService.discoverTools(serverId, timeout);
            Instant checkedAt = clock.instant();
            recordObservation(server, discovery, checkedAt);
            long latency = elapsedMillis(started);
            if (discovery.outcome() == McpOperationOutcome.SUCCESS) {
                return McpHealthProbeResult.success(checkedAt, latency);
            }
            McpHealthProbeCategory category = category(discovery);
            return McpHealthProbeResult.failure(category, checkedAt, category.name(), latency);
        } catch (RuntimeException ignored) {
            long latency = elapsedMillis(started);
            McpHealthProbeCategory category = McpHealthProbeResult.classify(ignored);
            Instant checkedAt = clock.instant();
            recordObservation(server, McpToolCallResult.failure(McpOperationOutcome.CONNECTOR_ERROR,
                    category.name()), checkedAt);
            return McpHealthProbeResult.failure(category, checkedAt, category.name(), latency);
        }
    }

    public McpHealthProbeResult health(UUID serverId, Duration timeout) {
        return probe(serverId, timeout);
    }

    private static McpHealthProbeCategory category(McpToolCallResult discovery) {
        return switch (discovery.outcome()) {
            case DENIED -> McpHealthProbeCategory.POLICY_REJECTED;
            case TIMEOUT -> McpHealthProbeCategory.TIMEOUT;
            case UNSUPPORTED -> McpHealthProbeCategory.PROTOCOL_ERROR;
            case CONNECTOR_ERROR -> McpHealthProbeResult.classify(discovery.classification());
            case SUCCESS -> McpHealthProbeCategory.SUCCESS;
        };
    }

    private void recordObservation(McpServerRecord server, McpToolCallResult result, Instant observedAt) {
        if (server == null) return;
        boolean healthy = result.outcome() == McpOperationOutcome.SUCCESS;
        String failureCategory = healthy ? "SUCCESS" : category(result).name();
        try {
            observations.record(new McpDiscoveryObservation(server.id(), server.version(),
                    instanceProperties.getInstanceId(), healthy ? toolsDigest(result) : "", healthy,
                    failureCategory, observedAt, observedAt.plus(instanceProperties.getObservationTtl())));
        } catch (RuntimeException ignored) {
            // Observation persistence is diagnostic and must not alter probe availability.
        }
    }

    private static String toolsDigest(McpToolCallResult result) {
        if (!(result.value() instanceof List<?> tools)
                || tools.stream().anyMatch(value -> !(value instanceof McpToolDescriptor))) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            tools.stream().map(McpToolDescriptor.class::cast)
                    .sorted(Comparator.comparing(McpToolDescriptor::name))
                    .forEach(tool -> {
                        digest.update(tool.name().getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                        digest.update(tool.description().getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                        digest.update(tool.inputSchemaJson().getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) '\n');
                    });
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private static final class NoopObservationPort implements McpDiscoveryObservationPort {
        @Override
        public void record(McpDiscoveryObservation observation) {
        }

        @Override
        public List<McpDiscoveryObservation> find(UUID serverId, long serverRevision) {
            return List.of();
        }
    }
}
