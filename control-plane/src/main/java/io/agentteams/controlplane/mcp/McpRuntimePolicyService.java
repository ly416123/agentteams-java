package io.agentteams.controlplane.mcp;

import io.agentteams.controlplane.security.OutboundPolicyValidator;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Runtime gate to be called by every MCP connector before network/tool execution. */
@Service
public final class McpRuntimePolicyService {
    private final OutboundPolicyValidator validator;

    public McpRuntimePolicyService() {
        this(new OutboundPolicyValidator());
    }

    McpRuntimePolicyService(OutboundPolicyValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public Authorization authorize(McpServerRecord server, String toolName, Duration timeout) {
        Objects.requireNonNull(server, "server");
        if (!server.enabled()) {
            return new Authorization(false, "SERVER_DISABLED");
        }
        if (server.healthStatus() == McpHealthStatus.UNHEALTHY) {
            return new Authorization(false, "SERVER_UNHEALTHY");
        }
        try {
            validator.validateEndpoint(server.endpoint(), server.outboundPolicy());
            validator.validateTimeout(timeout, server.outboundPolicy());
            validator.validateTool(toolName, server.outboundPolicy());
            return new Authorization(true, "ALLOWED");
        } catch (RuntimeException denied) {
            return new Authorization(false, "OUTBOUND_POLICY_DENIED");
        }
    }

    public record Authorization(boolean allowed, String classification) {
        public Authorization {
            Objects.requireNonNull(classification, "classification");
        }
    }
}
