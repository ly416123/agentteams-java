package io.agentteams.controlplane.mcp;

import io.agentteams.controlplane.security.OutboundPolicyValidator;
import io.agentteams.observability.ControlPlaneMetrics;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Runtime gate to be called by every MCP connector before network/tool execution. */
@Service
public final class McpRuntimePolicyService {
    private final OutboundPolicyValidator validator;
    private final ControlPlaneMetrics metrics;

    public McpRuntimePolicyService() {
        this(new OutboundPolicyValidator(), null);
    }

    McpRuntimePolicyService(OutboundPolicyValidator validator) {
        this(validator, null);
    }

    @Autowired
    public McpRuntimePolicyService(ObjectProvider<ControlPlaneMetrics> metrics) {
        this(new OutboundPolicyValidator(), metrics.getIfAvailable());
    }

    private McpRuntimePolicyService(OutboundPolicyValidator validator, ControlPlaneMetrics metrics) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.metrics = metrics;
    }

    public Authorization authorize(McpServerRecord server, String toolName, Duration timeout) {
        return authorize(server, timeout, () -> validator.validateTool(toolName, server.outboundPolicy()));
    }

    /**
     * Authorizes a tools/list operation. Discovery is an outbound operation, but is not itself a
     * tool and therefore must not be forced through the tool allow-list.
     */
    public Authorization authorizeDiscovery(McpServerRecord server, Duration timeout) {
        return authorize(server, timeout, () -> { });
    }

    private Authorization authorize(McpServerRecord server, Duration timeout, Runnable operationPolicy) {
        Objects.requireNonNull(server, "server");
        if (!server.enabled()) {
            countDenied();
            return new Authorization(false, "SERVER_DISABLED");
        }
        if (server.healthStatus() == McpHealthStatus.UNHEALTHY) {
            countDenied();
            return new Authorization(false, "SERVER_UNHEALTHY");
        }
        try {
            validator.validateEndpoint(server.endpoint(), server.outboundPolicy());
            validator.validateTimeout(timeout, server.outboundPolicy());
            operationPolicy.run();
            if (metrics != null) metrics.mcpPolicyAllowed();
            return new Authorization(true, "ALLOWED");
        } catch (RuntimeException denied) {
            countDenied();
            return new Authorization(false, "OUTBOUND_POLICY_DENIED");
        }
    }

    private void countDenied() {
        if (metrics != null) metrics.mcpPolicyDenied();
    }

    public record Authorization(boolean allowed, String classification) {
        public Authorization {
            Objects.requireNonNull(classification, "classification");
        }
    }
}
