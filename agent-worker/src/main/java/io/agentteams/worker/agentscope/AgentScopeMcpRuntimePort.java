package io.agentteams.worker.agentscope;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentteams.runtime.RuntimeMcpServer;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Objects;

/** AgentScope adapter for the Worker MCP runtime port. */
public final class AgentScopeMcpRuntimePort implements McpRuntimePort {
    private final McpCredentialProvider credentials;
    private final McpClientFactory clients;

    public AgentScopeMcpRuntimePort(McpCredentialProvider credentials) {
        this(credentials, AgentScopeMcpRuntimePort::buildClient);
    }

    AgentScopeMcpRuntimePort(McpCredentialProvider credentials, McpClientFactory clients) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clients = Objects.requireNonNull(clients, "clients");
    }

    @Override
    public void configure(Toolkit toolkit, List<RuntimeMcpServer> servers) {
        Objects.requireNonNull(toolkit, "toolkit");
        if (servers == null || servers.isEmpty()) return;
        for (RuntimeMcpServer server : servers) {
            try {
                McpClientWrapper client = Objects.requireNonNull(clients.create(server, credentials), "MCP client");
                toolkit.registration().mcpClient(client).apply();
            } catch (RuntimeException error) {
                throw new IllegalStateException("MCP_RUNTIME_UNAVAILABLE");
            }
        }
    }

    private static McpClientWrapper buildClient(RuntimeMcpServer server, McpCredentialProvider credentials) {
        McpClientBuilder builder = McpClientBuilder.create("agentteams-" + server.reference());
        if (server.transport().equals("SSE")) {
            builder.sseTransport(server.endpoint());
        } else {
            builder.streamableHttpTransport(server.endpoint());
        }
        if (server.credentialRef() != null) {
            String credentialRef = server.credentialRef();
            builder.httpRequestCustomizer((request, method, uri, body, context) ->
                    addAuthorization(request, credentials, credentialRef));
        }
        return builder.buildAsync().block();
    }

    static void addAuthorization(HttpRequest.Builder request, McpCredentialProvider credentials,
            String credentialRef) {
        String token = credentials.resolve(credentialRef)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("MCP_CREDENTIAL_UNAVAILABLE"));
        request.header("Authorization", "Bearer " + token);
    }

    @FunctionalInterface
    interface McpClientFactory {
        McpClientWrapper create(RuntimeMcpServer server, McpCredentialProvider credentials);
    }
}
