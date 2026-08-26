package io.agentteams.worker.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentteams.application.api.SandboxRuntimePort;
import io.agentteams.application.api.SandboxStatus;
import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.RuntimeTask;
import io.agentteams.worker.SandboxStateProbePort;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Flux;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfiguredAgentScopeHarnessFactoryTest {
    @TempDir
    Path root;

    @Test
    void rejectsMissingModelConfigurationBeforeCreatingAWorkerSession() {
        assertThatThrownBy(() -> new ConfiguredAgentScopeHarnessFactory("", null, Path.of("/tmp/worker")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AgentScope model must be configured");
    }

    @Test
    void ignoresUntrustedWorkspacePathAndUsesWorkspaceBindingFactory() throws Exception {
        AgentScopeWorkspaceFactory workspaceFactory = AgentScopeWorkspaceFactory.testOnly(
                new ReadySandboxRuntime(), Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC), root);
        ConfiguredAgentScopeHarnessFactory factory = new ConfiguredAgentScopeHarnessFactory(
                new TestModel(), workspaceFactory, root);
        RuntimeTask task = new RuntimeTask(UUID.randomUUID(), "chat", "hello", Map.of(
                "tenantId", "tenant-a", "projectId", "project-a", "teamId", "team-a",
                "agentId", "agent-a", "attemptId", "attempt-a",
                "workspacePath", "/tmp/attacker-controlled-workspace"));

        var harness = factory.create(task, new AgentRuntimeContext("agentscope", 1,
                Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC), ignored -> { }, Map.of()));

        assertThat(harness).isNotNull();
        harness.close();
    }

    @Test
    void usesInjectedSandboxProbeThroughTheControlledWorkspaceFactory() {
        AtomicBoolean inspected = new AtomicBoolean();
        UUID sandboxId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        SandboxStateProbePort probe = (observedSandboxId, taskId, attemptId) -> {
            inspected.set(observedSandboxId.equals(sandboxId));
            return new SandboxStateProbePort.SandboxExecutionState(SandboxStatus.READY,
                    "sandbox://provider/a", Instant.parse("2026-08-26T00:01:00Z"));
        };
        InMemorySandboxWorkspaceOwnershipPort delegate = new InMemorySandboxWorkspaceOwnershipPort();
        SandboxWorkspaceOwnershipPort ownership = new SandboxWorkspaceOwnershipPort() {
            @Override public boolean durable() { return true; }
            @Override public Optional<WorkspaceOwner> findSandboxOwner(String id) {
                return delegate.findSandboxOwner(id);
            }
            @Override public Optional<WorkspaceOwner> findWorkspaceOwner(Path path) {
                return delegate.findWorkspaceOwner(path);
            }
            @Override public void claimSandbox(String id, WorkspaceOwner owner) {
                delegate.claimSandbox(id, owner);
            }
            @Override public void claimWorkspace(Path path, WorkspaceOwner owner) {
                delegate.claimWorkspace(path, owner);
            }
        };
        AgentScopeWorkspaceFactory workspaceFactory = new AgentScopeWorkspaceFactory(probe,
                Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC), root, ownership);
        ConfiguredAgentScopeHarnessFactory factory = new ConfiguredAgentScopeHarnessFactory(
                new TestModel(), workspaceFactory, root);
        RuntimeTask task = new RuntimeTask(UUID.randomUUID(), "chat", "hello", Map.ofEntries(
                Map.entry("tenantId", "tenant-a"), Map.entry("projectId", "project-a"),
                Map.entry("teamId", "team-a"), Map.entry("agentId", "agent-a"),
                Map.entry("attemptId", "attempt-a"), Map.entry("sandboxId", sandboxId.toString()),
                Map.entry("providerSandboxId", "provider-a"), Map.entry("profile", "ISOLATED"),
                Map.entry("status", "READY"), Map.entry("endpointRef", "sandbox://provider/a"),
                Map.entry("expiresAt", "2026-08-26T00:01:00Z")));

        var harness = factory.create(task, new AgentRuntimeContext("agentscope", 1,
                Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC), ignored -> { }, Map.of()));

        assertThat(inspected).isTrue();
        harness.close();
    }

    private static final class ReadySandboxRuntime implements SandboxRuntimePort {
        @Override
        public SandboxStatus inspect(String providerSandboxId) {
            return SandboxStatus.READY;
        }
    }

    private static final class TestModel implements Model {
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<io.agentscope.core.model.ToolSchema> tools,
                GenerateOptions options) {
            ContentBlock content = io.agentscope.core.message.TextBlock.builder().text("ok").build();
            return Flux.just(ChatResponse.builder().id("test").content(List.of(content))
                    .usage(new ChatUsage(1, 1, 0.0)).finishReason("stop").build());
        }

        @Override
        public String getModelName() {
            return "test-model";
        }
    }
}
