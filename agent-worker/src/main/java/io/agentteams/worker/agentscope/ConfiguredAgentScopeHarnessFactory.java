package io.agentteams.worker.agentscope;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentteams.application.api.SandboxStatus;
import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.RuntimeTask;
import io.agentteams.worker.SandboxStateProbePort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Production AgentScope Harness factory with bounded workspace and sandbox observation. */
public final class ConfiguredAgentScopeHarnessFactory implements AgentScopeHarnessFactory {
    private final Model model;
    private final Path workspaceRoot;
    private final SandboxStateProbePort sandboxStateProbe;

    public ConfiguredAgentScopeHarnessFactory(String modelId, Path workspaceRoot) {
        this(resolveModel(modelId), workspaceRoot, null);
    }

    public ConfiguredAgentScopeHarnessFactory(Model model, Path workspaceRoot) {
        this(model, workspaceRoot, null);
    }

    public ConfiguredAgentScopeHarnessFactory(Model model, Path workspaceRoot,
            SandboxStateProbePort sandboxStateProbe) {
        this.model = Objects.requireNonNull(model, "model");
        this.workspaceRoot = normalizeRoot(workspaceRoot);
        this.sandboxStateProbe = sandboxStateProbe;
    }

    @Override
    public HarnessAgent create(RuntimeTask task, AgentRuntimeContext context) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(context, "context");
        validateSandbox(task, context);
        Path workspace = workspace(task);
        try {
            Files.createDirectories(workspace);
        } catch (IOException error) {
            throw new IllegalStateException("unable to create AgentScope workspace", error);
        }
        String agentId = context.configuration().getOrDefault("worker_id",
                task.metadata().getOrDefault("agentId", "agent-worker"));
        return HarnessAgent.builder()
                .name("agentteams-worker")
                .agentId(agentId)
                .defaultSessionId(required(task, "attemptId"))
                .model(model)
                .workspace(workspace)
                .disableShellTool()
                .disableSubagents()
                .disableSessionPersistence()
                .maxIters(10)
                .build();
    }

    private void validateSandbox(RuntimeTask task, AgentRuntimeContext context) {
        String sandboxId = task.metadata().get("sandboxId");
        if (sandboxId == null || sandboxId.isBlank()) {
            return;
        }
        if (sandboxStateProbe == null) {
            throw new IllegalArgumentException("sandbox state probe is not configured");
        }
        UUID id = parseUuid(sandboxId, "sandboxId");
        UUID attemptId = parseUuid(required(task, "attemptId"), "attemptId");
        SandboxStateProbePort.SandboxExecutionState state = sandboxStateProbe.inspect(id, task.id(), attemptId);
        if (state == null || (state.status() != SandboxStatus.READY && state.status() != SandboxStatus.RUNNING)) {
            throw new IllegalArgumentException("sandbox is not usable");
        }
        if (!context.clock().instant().isBefore(state.expiresAt())) {
            throw new IllegalArgumentException("sandbox state is expired");
        }
    }

    private Path workspace(RuntimeTask task) {
        String requested = task.metadata().get("workspacePath");
        Path candidate = requested == null || requested.isBlank()
                ? workspaceRoot.resolve(task.id().toString())
                : Path.of(requested.trim());
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("workspace path is outside the configured root");
        }
        return normalized;
    }

    private static Model resolveModel(String modelId) {
        if (modelId == null || modelId.isBlank() || "unknown".equalsIgnoreCase(modelId.trim())) {
            throw new IllegalArgumentException("AgentScope model must be configured");
        }
        return ModelRegistry.resolve(modelId.trim());
    }

    private static Path normalizeRoot(Path root) {
        Objects.requireNonNull(root, "workspaceRoot");
        return root.toAbsolutePath().normalize();
    }

    private static String required(RuntimeTask task, String field) {
        String value = task.metadata().get(field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("task metadata must contain " + field);
        }
        return value.trim();
    }

    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(field + " must be a UUID", error);
        }
    }
}
