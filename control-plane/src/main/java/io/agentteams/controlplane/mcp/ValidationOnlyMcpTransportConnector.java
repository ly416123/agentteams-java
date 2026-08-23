package io.agentteams.controlplane.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Safe default when no concrete MCP network dependency is installed.
 * It validates routing and policy through the facade, but never opens a socket or fabricates data.
 */
@Component
public final class ValidationOnlyMcpTransportConnector implements McpTransportConnector {
    @Override
    public McpTransport transport() {
        return McpTransport.SSE;
    }

    @Override
    public boolean supports(McpTransport transport) {
        return transport == McpTransport.SSE || transport == McpTransport.STREAMABLE_HTTP;
    }

    @Override
    public boolean isFallback() {
        return true;
    }

    @Override
    public List<McpToolDescriptor> discoverTools(McpConnectorTarget target, Duration timeout) {
        throw new UnsupportedOperationException("MCP transport adapter is not configured");
    }

    @Override
    public Object callTool(McpConnectorTarget target, String toolName, Map<String, Object> arguments,
            Duration timeout) {
        throw new UnsupportedOperationException("MCP transport adapter is not configured");
    }
}
