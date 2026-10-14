package io.agentteams.controlplane.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Optional deployment adapter extension for MCP transports that need a runtime credential.
 *
 * <p>The credential reference is passed only to the adapter, which resolves it through its
 * deployment-owned secret provider. Secret material never enters {@link McpConnectorTarget} or
 * the generic connector SPI.</p>
 */
public interface CredentialAwareMcpTransportConnector {
    List<McpToolDescriptor> discoverTools(McpConnectorTarget target, String credentialRef, Duration timeout);

    Object callTool(McpConnectorTarget target, String toolName, Map<String, Object> arguments,
            String credentialRef, Duration timeout);
}
