package io.agentteams.controlplane.mcp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality MCP runtime metrics shared by the guard, discovery cache and connectors.
 *
 * <p>No server id, tool name, endpoint, credential, request argument or connector message is
 * used as a metric label. Connector classifications are reduced to the bounded HTTP failure
 * enum or the generic {@code CONNECTOR_EXCEPTION} bucket before they reach Micrometer.</p>
 */
@Component
public final class McpObservability {
    private static final String CACHE_METRIC = "agentteams.mcp.tools.list.cache";
    private static final String CONNECTOR_METRIC = "agentteams.mcp.connector.calls";
    private static final String CONNECTOR_LATENCY = "agentteams.mcp.connector.latency";

    private final MeterRegistry registry;

    @Autowired
    public McpObservability(ObjectProvider<MeterRegistry> registries) {
        this(registries.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** Compatibility constructor for focused unit tests and embedded callers. */
    public McpObservability(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Compatibility constructor that keeps non-Spring MCP tests self-contained. */
    public McpObservability() {
        this(new SimpleMeterRegistry());
    }

    public void runtimeRejected(String reason) {
        registry.counter("agentteams.mcp.runtime.rejections", "reason", runtimeReason(reason)).increment();
    }

    public void circuitOpened() {
        registry.counter("agentteams.mcp.runtime.circuit.opened").increment();
    }

    public void circuitRecovered() {
        registry.counter("agentteams.mcp.runtime.circuit.recovered").increment();
    }

    public void cacheHit() {
        cacheEvent("hit");
    }

    public void cacheMiss() {
        cacheEvent("miss");
    }

    public void cacheExpired() {
        cacheEvent("expired");
    }

    public void cacheInvalidated() {
        cacheEvent("invalidated");
    }

    public void cacheEvicted() {
        cacheEvent("evicted");
    }

    public Timer.Sample connectorStarted() {
        return Timer.start(registry);
    }

    public void connectorSucceeded(String operation, Timer.Sample sample) {
        connectorCompleted(operation, "success", "SUCCESS", sample);
    }

    public void connectorTimedOut(String operation, Timer.Sample sample) {
        connectorCompleted(operation, "timeout", "CONNECTOR_TIMEOUT", sample);
    }

    public void connectorUnsupported(String operation, Timer.Sample sample) {
        connectorCompleted(operation, "unsupported", "TRANSPORT_NOT_CONFIGURED", sample);
    }

    public void connectorFailed(String operation, String classification, Timer.Sample sample) {
        connectorCompleted(operation, "failure", connectorClassification(classification), sample);
    }

    private void cacheEvent(String event) {
        registry.counter(CACHE_METRIC, "event", event).increment();
    }

    private void connectorCompleted(String operation, String outcome, String classification,
            Timer.Sample sample) {
        String boundedOperation = operation.equals("tools_list") ? "tools_list" : "tool_call";
        String boundedOutcome = switch (outcome) {
            case "success", "timeout", "unsupported", "failure" -> outcome;
            default -> "failure";
        };
        registry.counter(CONNECTOR_METRIC, "operation", boundedOperation, "outcome", boundedOutcome,
                "classification", connectorClassification(classification)).increment();
        if (sample != null) {
            sample.stop(registry.timer(CONNECTOR_LATENCY, "operation", boundedOperation,
                    "outcome", boundedOutcome));
        }
    }

    private static String runtimeReason(String reason) {
        return McpRuntimeGuard.RATE_LIMITED.equals(reason) || McpRuntimeGuard.CIRCUIT_OPEN.equals(reason)
                ? reason : "UNKNOWN";
    }

    private static String connectorClassification(String classification) {
        if (classification == null || classification.isBlank()) return "CONNECTOR_EXCEPTION";
        try {
            return McpHttpFailureCategory.valueOf(classification.toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ignored) {
            return switch (classification) {
                case "SUCCESS", "CONNECTOR_TIMEOUT", "TRANSPORT_NOT_CONFIGURED" -> classification;
                default -> "CONNECTOR_EXCEPTION";
            };
        }
    }
}
