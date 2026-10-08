package io.agentteams.worker.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.runtime.RuntimeTask;
import io.agentteams.runtime.RuntimeMcpServer;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpExecutionContextTest {

    @Test
    void derivesTaskAttemptAndLeaseFromRuntimeAssignmentMetadata() {
        UUID taskId = UUID.randomUUID();
        RuntimeTask task = new RuntimeTask(taskId, "agentscope", "{}", Map.of(
                "attemptId", "attempt-7", "leaseId", "lease-9"));

        assertThat(McpExecutionContext.from(task))
                .isEqualTo(new McpExecutionContext(taskId, "attempt-7", "lease-9"));
    }

    @Test
    void refusesToCreateExecutionContextWithoutAttemptOrLease() {
        RuntimeTask task = new RuntimeTask(UUID.randomUUID(), "agentscope", "{}", Map.of("attemptId", "attempt-7"));

        assertThatThrownBy(() -> McpExecutionContext.from(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("task MCP execution context is incomplete");
    }

    @Test
    void addsAssignmentHeadersAndUniqueCallIdOnlyForPlatformTaskMcp() {
        McpExecutionContext context = new McpExecutionContext(UUID.randomUUID(), "attempt-7", "lease-9");
        HttpRequest.Builder platformRequest = HttpRequest.newBuilder(URI.create("https://mcp.example.test"));

        AgentScopeMcpRuntimePort.addTaskExecutionHeaders(platformRequest, context, true);

        HttpRequest platform = platformRequest.build();
        assertThat(platform.headers().firstValue("X-AgentTeams-Task-Id"))
                .contains(context.taskId().toString());
        assertThat(platform.headers().firstValue("X-AgentTeams-Attempt-Id")).contains("attempt-7");
        assertThat(platform.headers().firstValue("X-AgentTeams-Lease-Id")).contains("lease-9");
        assertThat(platform.headers().firstValue("X-AgentTeams-Call-Id"))
                .hasValueSatisfying(value -> assertThat(UUID.fromString(value)).isNotNull());

        HttpRequest.Builder externalRequest = HttpRequest.newBuilder(URI.create("https://external.example.test"));
        AgentScopeMcpRuntimePort.addTaskExecutionHeaders(externalRequest, context, false);

        assertThat(externalRequest.build().headers().map()).isEmpty();
    }

    @Test
    void passesExecutionContextOnlyToPlatformMcpClient() {
        Toolkit toolkit = mock(Toolkit.class);
        Toolkit.ToolRegistration registration = mock(Toolkit.ToolRegistration.class);
        when(toolkit.registration()).thenReturn(registration);
        when(registration.mcpClient(any())).thenReturn(registration);
        when(registration.enableTools(any())).thenReturn(registration);
        McpExecutionContext executionContext = new McpExecutionContext(UUID.randomUUID(), "attempt-7", "lease-9");
        AtomicReference<McpExecutionContext> received = new AtomicReference<>();
        McpClientWrapper client = mock(McpClientWrapper.class);
        AgentScopeMcpRuntimePort port = new AgentScopeMcpRuntimePort(reference -> java.util.Optional.of("token"),
                (server, credentials, context) -> {
                    received.set(context);
                    return client;
                });
        RuntimeMcpServer platformServer = new RuntimeMcpServer("platform", 1, "STREAMABLE_HTTP",
                "http://agentteams-mcp-tools:8090/mcp", "MCP_TOOLS_TOKEN", "sha256:platform");

        port.configure(toolkit, List.of(platformServer), List.of(), Set.of("plan_subtasks"), executionContext);

        assertThat(received.get()).isEqualTo(executionContext);
    }

    @Test
    void failsClosedWhenPlatformMcpHasNoExecutionContext() {
        Toolkit toolkit = mock(Toolkit.class);
        AgentScopeMcpRuntimePort port = new AgentScopeMcpRuntimePort(reference -> java.util.Optional.of("token"),
                (server, credentials) -> mock(McpClientWrapper.class));
        RuntimeMcpServer platformServer = new RuntimeMcpServer("platform", 1, "STREAMABLE_HTTP",
                "http://agentteams-mcp-tools:8090/mcp", "MCP_TOOLS_TOKEN", "sha256:platform");

        assertThatThrownBy(() -> port.configure(toolkit, List.of(platformServer), List.of(),
                Set.of("plan_subtasks"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("task MCP execution context is required");
    }
}
