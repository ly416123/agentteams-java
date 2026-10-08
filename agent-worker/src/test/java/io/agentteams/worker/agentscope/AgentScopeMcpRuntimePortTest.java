package io.agentteams.worker.agentscope;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentteams.application.api.SandboxPolicy;
import io.agentteams.application.api.SandboxProfile;
import io.agentteams.application.api.SkillCapabilityPolicy;
import io.agentteams.runtime.RuntimeMcpServer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Test
    void registersOnlyServersAndToolsAllowedByTheSkillPolicy() {
        Toolkit toolkit = mock(Toolkit.class);
        Toolkit.ToolRegistration registration = mock(Toolkit.ToolRegistration.class);
        when(toolkit.registration()).thenReturn(registration);
        when(registration.mcpClient(any())).thenReturn(registration);
        when(registration.enableTools(any())).thenReturn(registration);
        McpClientWrapper client = mock(McpClientWrapper.class);
        AtomicReference<RuntimeMcpServer> received = new AtomicReference<>();
        McpRuntimePort port = new AgentScopeMcpRuntimePort(reference -> java.util.Optional.empty(),
                (server, provider) -> {
                    received.set(server);
                    return client;
                });
        SkillCapabilityPolicy policy = new SkillCapabilityPolicy(
                SandboxProfile.ISOLATED, 500, 512, 1024, Duration.ofMinutes(5),
                java.util.Set.of("server-7"), java.util.Set.of("mcp.example.test"),
                java.util.Set.of("search"), true, SandboxPolicy.NetworkPolicy.RESTRICTED);

        port.configure(toolkit, List.of(server()), List.of(policy));

        verify(registration).mcpClient(client);
        verify(registration).enableTools(List.of("search"));
        verify(registration).apply();
        org.assertj.core.api.Assertions.assertThat(received.get()).isEqualTo(server());
    }

    @Test
    void allowsNamedPlatformMcpWithPlatformCredentialAndExternalTargetDomainAllowlist() {
        Toolkit toolkit = mock(Toolkit.class);
        Toolkit.ToolRegistration registration = mock(Toolkit.ToolRegistration.class);
        when(toolkit.registration()).thenReturn(registration);
        when(registration.mcpClient(any())).thenReturn(registration);
        when(registration.enableTools(any())).thenReturn(registration);
        McpClientWrapper client = mock(McpClientWrapper.class);
        AtomicReference<RuntimeMcpServer> received = new AtomicReference<>();
        McpRuntimePort port = new AgentScopeMcpRuntimePort(reference -> java.util.Optional.empty(),
                (server, provider) -> {
                    received.set(server);
                    return client;
                });
        SkillCapabilityPolicy policy = new SkillCapabilityPolicy(
                SandboxProfile.ISOLATED, 250, 256, 512, Duration.ofMinutes(30),
                Set.of("agentteams-web-search"), Set.of("www.w3.org"), Set.of("web_search"), false,
                SandboxPolicy.NetworkPolicy.RESTRICTED);
        RuntimeMcpServer platformSearch = new RuntimeMcpServer("agentteams-web-search", 0,
                "STREAMABLE_HTTP", "http://agentteams-mcp-tools:8090/mcp", "MCP_WEB_SEARCH_TOKEN",
                "sha256:platform-search");

        port.configure(toolkit, List.of(platformSearch), List.of(policy));

        assertThat(received.get()).isEqualTo(platformSearch);
        verify(registration).enableTools(List.of("web_search"));
        verify(registration).apply();
    }

    @Test
    void stillRequiresAllowedDomainForExternalMcpEndpoints() {
        Toolkit toolkit = mock(Toolkit.class);
        AtomicReference<Boolean> created = new AtomicReference<>(false);
        McpRuntimePort port = new AgentScopeMcpRuntimePort(reference -> java.util.Optional.empty(),
                (server, provider) -> {
                    created.set(true);
                    return mock(McpClientWrapper.class);
                });
        SkillCapabilityPolicy policy = new SkillCapabilityPolicy(
                SandboxProfile.ISOLATED, 250, 256, 512, Duration.ofMinutes(30),
                Set.of("external-search"), Set.of("www.w3.org"), Set.of("web_search"), false,
                SandboxPolicy.NetworkPolicy.RESTRICTED);
        RuntimeMcpServer external = new RuntimeMcpServer("external-search", 0,
                "STREAMABLE_HTTP", "https://mcp.example.test/mcp", null, "sha256:external-search");

        port.configure(toolkit, List.of(external), List.of(policy));

        assertThat(created).hasValue(false);
        verify(toolkit, never()).registration();
    }

    @Test
    void doesNotCreateAClientWhenSkillPolicyDeniesMcpNetworkOrCredentials() {
        Toolkit toolkit = mock(Toolkit.class);
        AtomicReference<Boolean> created = new AtomicReference<>(false);
        McpRuntimePort port = new AgentScopeMcpRuntimePort(reference -> java.util.Optional.empty(),
                (server, provider) -> {
                    created.set(true);
                    return mock(McpClientWrapper.class);
                });
        SkillCapabilityPolicy policy = new SkillCapabilityPolicy(
                SandboxProfile.NONE, 250, 256, 512, Duration.ofMinutes(5),
                java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), false,
                SandboxPolicy.NetworkPolicy.DENY_ALL);

        port.configure(toolkit, List.of(server()), List.of(policy));

        org.assertj.core.api.Assertions.assertThat(created).hasValue(false);
    }

    @Test
    void exposesOnlyDeclaredPlatformTaskToolDespiteDefaultSkillDenyPolicy() {
        Toolkit toolkit = mock(Toolkit.class);
        Toolkit.ToolRegistration registration = mock(Toolkit.ToolRegistration.class);
        when(toolkit.registration()).thenReturn(registration);
        when(registration.mcpClient(any())).thenReturn(registration);
        when(registration.enableTools(any())).thenReturn(registration);
        McpClientWrapper client = mock(McpClientWrapper.class);
        McpRuntimePort port = new AgentScopeMcpRuntimePort(reference -> java.util.Optional.of("task-token"),
                (server, provider) -> client);
        SkillCapabilityPolicy defaultPolicy = new SkillCapabilityPolicy(
                SandboxProfile.NONE, 250, 256, 512, Duration.ofMinutes(5),
                Set.of(), Set.of(), Set.of(), false, SandboxPolicy.NetworkPolicy.DENY_ALL);
        RuntimeMcpServer taskServer = new RuntimeMcpServer("aaefa1d5-f4dc-4399-a20a-5a7b827b5df8", 3,
                "STREAMABLE_HTTP", "http://agentteams-mcp-tools:8090/mcp", "MCP_TOOLS_TOKEN", "sha256:task");

        port.configure(toolkit, List.of(taskServer), List.of(defaultPolicy), Set.of("plan_subtasks"));

        verify(registration).mcpClient(client);
        verify(registration).enableTools(List.of("plan_subtasks"));
        verify(registration).apply();
    }

    @Test
    void createsPlatformTaskServerWhenAttemptRequiresToolAndNoServerIsBound() {
        Toolkit toolkit = mock(Toolkit.class);
        Toolkit.ToolRegistration registration = mock(Toolkit.ToolRegistration.class);
        when(toolkit.registration()).thenReturn(registration);
        when(registration.mcpClient(any())).thenReturn(registration);
        when(registration.enableTools(any())).thenReturn(registration);
        McpClientWrapper client = mock(McpClientWrapper.class);
        AtomicReference<RuntimeMcpServer> received = new AtomicReference<>();
        McpRuntimePort port = new AgentScopeMcpRuntimePort(reference -> java.util.Optional.of("task-token"),
                (server, provider) -> {
                    received.set(server);
                    return client;
                });

        port.configure(toolkit, List.of(), List.of(), Set.of("plan_subtasks"));

        assertThat(received.get()).isNotNull();
        assertThat(received.get().endpoint()).isEqualTo("http://agentteams-mcp-tools:8090/mcp");
        assertThat(received.get().credentialRef()).isEqualTo("MCP_TOOLS_TOKEN");
        verify(registration).mcpClient(client);
        verify(registration).enableTools(List.of("plan_subtasks"));
        verify(registration).apply();
    }

    @Test
    void exposesPdfArtifactToolOnlyWhenDeclaredForTheAttempt() {
        Toolkit toolkit = mock(Toolkit.class);
        Toolkit.ToolRegistration registration = mock(Toolkit.ToolRegistration.class);
        when(toolkit.registration()).thenReturn(registration);
        when(registration.mcpClient(any())).thenReturn(registration);
        when(registration.enableTools(any())).thenReturn(registration);
        McpClientWrapper client = mock(McpClientWrapper.class);
        McpRuntimePort port = new AgentScopeMcpRuntimePort(reference -> java.util.Optional.of("task-token"),
                (server, provider) -> client);
        SkillCapabilityPolicy defaultPolicy = new SkillCapabilityPolicy(
                SandboxProfile.NONE, 250, 256, 512, Duration.ofMinutes(5),
                Set.of(), Set.of(), Set.of(), false, SandboxPolicy.NetworkPolicy.DENY_ALL);
        RuntimeMcpServer taskServer = new RuntimeMcpServer("aaefa1d5-f4dc-4399-a20a-5a7b827b5df8", 3,
                "STREAMABLE_HTTP", "http://agentteams-mcp-tools:8090/mcp", "MCP_TOOLS_TOKEN", "sha256:task");

        port.configure(toolkit, List.of(taskServer), List.of(defaultPolicy),
                Set.of("create_task_pdf_artifact"));

        verify(registration).mcpClient(client);
        verify(registration).enableTools(List.of("create_task_pdf_artifact"));
        verify(registration).apply();
    }

    private static RuntimeMcpServer server() {
        return new RuntimeMcpServer("server-7", 7, "STREAMABLE_HTTP",
                "https://mcp.example.test/http", "MCP_SERVER_TOKEN", "sha256:policy");
    }
}
