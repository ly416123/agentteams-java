package io.agentteams.controlplane.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Transport adapter SPI for MCP runtime integrations.
 *
 * <p>Implementations may use SSE or Streamable HTTP, but must not receive or return a
 * control-plane credentialRef. Authentication belongs to the concrete deployment adapter.</p>
 */
public interface McpTransportConnector {
    McpTransport transport();

    /** Allows one validation-only adapter to cover all known transports. */
    default boolean supports(McpTransport candidate) {
        return transport() == candidate;
    }

    /** Marks a safe default that may be used when no concrete connector is registered. */
    default boolean isFallback() {
        return false;
    }

    List<McpToolDescriptor> discoverTools(McpConnectorTarget target, Duration timeout);

    Object callTool(McpConnectorTarget target, String toolName, Map<String, Object> arguments, Duration timeout);
}
