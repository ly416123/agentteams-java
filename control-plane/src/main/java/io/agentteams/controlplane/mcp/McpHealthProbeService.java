package io.agentteams.controlplane.mcp;

import java.time.Clock;
import java.time.Duration;
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
    private final Clock clock;

    @Autowired
    public McpHealthProbeService(McpToolExecutionService executionService, Clock clock) {
        this.executionService = Objects.requireNonNull(executionService, "executionService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Compatibility constructor for non-Spring callers. */
    public McpHealthProbeService(McpToolExecutionService executionService) {
        this(executionService, Clock.systemUTC());
    }

    public McpHealthProbeResult probe(UUID serverId, Duration timeout) {
        Objects.requireNonNull(serverId, "serverId");
        long started = System.nanoTime();
        try {
            McpToolCallResult discovery = executionService.discoverTools(serverId, timeout);
            long latency = elapsedMillis(started);
            if (discovery.outcome() == McpOperationOutcome.SUCCESS) {
                return McpHealthProbeResult.success(clock.instant(), latency);
            }
            McpHealthProbeCategory category = category(discovery);
            return McpHealthProbeResult.failure(category, clock.instant(), category.name(), latency);
        } catch (RuntimeException ignored) {
            long latency = elapsedMillis(started);
            McpHealthProbeCategory category = McpHealthProbeResult.classify(ignored);
            return McpHealthProbeResult.failure(category, clock.instant(), category.name(), latency);
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

    private static long elapsedMillis(long started) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
}
