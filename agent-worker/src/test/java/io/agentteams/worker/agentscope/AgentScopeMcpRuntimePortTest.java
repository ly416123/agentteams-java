package io.agentteams.worker.agentscope;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentteams.runtime.RuntimeMcpServer;
import java.util.List;
import java.util.Map;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentScopeMcpRuntimePortTest {
    @Test
    void registersMcpClientsWithDynamicCredentialResolution() {
        Toolkit toolkit = mock(Toolkit.class);
        Toolkit.ToolRegistration registration = mock(Toolkit.ToolRegistration.class);
        when(toolkit.registration()).thenReturn(registration);
        when(registration.mcpClient(any())).thenReturn(registration);
        McpClientWrapper client = mock(McpClientWrapper.class);
        AtomicReference<RuntimeMcpServer> received = new AtomicReference<>();
        McpCredentialProvider credentials = mock(McpCredentialProvider.class);
        McpRuntimePort port = new AgentScopeMcpRuntimePort(credentials,
                (server, provider) -> {
                    received.set(server);
                    return client;
                });

        port.configure(toolkit, List.of(server()));

        verify(registration).mcpClient(client);
        verify(registration).apply();
        verify(credentials, never()).resolve(any());
        org.assertj.core.api.Assertions.assertThat(received.get().reference()).isEqualTo("server-7");
    }

    @Test
    void hidesProviderFailureBehindStableRuntimeClassification() {
        Toolkit toolkit = mock(Toolkit.class);
        McpRuntimePort port = new AgentScopeMcpRuntimePort(
                reference -> java.util.Optional.empty(),
                (server, provider) -> { throw new IllegalStateException("https://secret.example/token"); });

        assertThatThrownBy(() -> port.configure(toolkit, List.of(server())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MCP_RUNTIME_UNAVAILABLE");
    }

    @Test
    void ignoresEmptyBindingCollection() {
        Toolkit toolkit = mock(Toolkit.class);
        McpRuntimePort port = new AgentScopeMcpRuntimePort(reference -> java.util.Optional.empty());

        port.configure(toolkit, List.of());

        verify(toolkit, never()).registration();
    }

    @Test
    void injectsCredentialOnlyAtRequestTime() {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://mcp.example.test/http"));

        AgentScopeMcpRuntimePort.addAuthorization(request, reference ->
                java.util.Optional.of("secret-value"), "MCP_SERVER_TOKEN");

        org.assertj.core.api.Assertions.assertThat(request.build().headers().firstValue("Authorization"))
                .contains("Bearer secret-value");
    }

    private static RuntimeMcpServer server() {
        return new RuntimeMcpServer("server-7", 7, "STREAMABLE_HTTP",
                "https://mcp.example.test/http", "MCP_SERVER_TOKEN", "sha256:policy");
    }
}
