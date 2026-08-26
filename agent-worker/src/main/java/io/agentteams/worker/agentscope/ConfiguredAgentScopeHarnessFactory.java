package io.agentteams.worker.agentscope;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentteams.application.api.SandboxHandle;
import io.agentteams.application.api.SandboxProfile;
import io.agentteams.application.api.SandboxStatus;
import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.RuntimeTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Production AgentScope Harness factory with bounded workspace and sandbox observation. */
public final class ConfiguredAgentScopeHarnessFactory implements AgentScopeHarnessFactory {
    private final Model model;
    private final Path workspaceRoot;
    private final AgentScopeWorkspaceFactory workspaceFactory;
    private final Map<UUID, AgentScopeWorkspaceFactory.WorkspaceBinding> bindings =
            new ConcurrentHashMap<>();

    public ConfiguredAgentScopeHarnessFactory(String modelId,
            AgentScopeWorkspaceFactory workspaceFactory, Path workspaceRoot) {
        this(resolveModel(modelId), workspaceFactory, workspaceRoot);
    }

    public ConfiguredAgentScopeHarnessFactory(Model model, AgentScopeWorkspaceFactory workspaceFactory,
            Path workspaceRoot) {
        this.model = Objects.requireNonNull(model, "model");
        this.workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory");
        this.workspaceRoot = normalizeRoot(workspaceRoot);
    }

    public WorkspaceActiveGuard activeGuard() {
        return (task, context) -> {
            AgentScopeWorkspaceFactory.WorkspaceBinding binding = bindings.get(task.id());
            if (binding != null) {
                workspaceFactory.assertUsable(binding, task, context);
            }
        };
    }

    @Override
    public HarnessAgent create(RuntimeTask task, AgentRuntimeContext context) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(context, "context");
        AgentScopeWorkspaceFactory.WorkspaceBinding binding = resolveBinding(task, context);
        bindings.put(task.id(), binding);
        Path workspace = binding.workspacePath().orElseGet(() -> workspaceRoot.resolve(task.id().toString()));
        workspace = workspace.toAbsolutePath().normalize();
        if (!workspace.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("resolved workspace is outside the configured root");
        }
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

    private AgentScopeWorkspaceFactory.WorkspaceBinding resolveBinding(RuntimeTask task,
            AgentRuntimeContext context) {
        if (!hasWorkspaceScope(task)) {
            return new AgentScopeWorkspaceFactory.WorkspaceBinding(
                    "agentscope-local-" + task.id(), SandboxProfile.NONE,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        String sandboxId = task.metadata().get("sandboxId");
        if (sandboxId == null || sandboxId.isBlank()) {
            return workspaceFactory.resolve(task, context, Optional.empty());
        }
        SandboxHandle handle = new SandboxHandle(
                task.metadata().getOrDefault("providerSandboxId", sandboxId),
                SandboxProfile.valueOf(required(task, "profile")),
                SandboxStatus.valueOf(required(task, "status")),
                required(task, "endpointRef"),
                Instant.parse(required(task, "expiresAt")),
                task.id(), parseAttemptId(required(task, "attemptId")));
        return workspaceFactory.resolve(task, context, handle.profile(), Optional.of(handle));
    }

    private static boolean hasWorkspaceScope(RuntimeTask task) {
        return task.metadata().keySet().containsAll(Set.of("tenantId", "projectId", "teamId", "agentId",
                "attemptId"));
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

    private static UUID parseAttemptId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
