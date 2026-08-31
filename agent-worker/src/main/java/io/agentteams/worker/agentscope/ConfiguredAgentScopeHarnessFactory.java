package io.agentteams.worker.agentscope;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentteams.application.api.SandboxHandle;
import io.agentteams.application.api.SandboxProfile;
import io.agentteams.application.api.SandboxStatus;
import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.RuntimeConfigSnapshot;
import io.agentteams.runtime.RuntimeMcpServer;
import io.agentteams.application.api.SkillCapabilityPolicy;
import io.agentteams.runtime.RuntimeTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import io.agentscope.core.tool.Toolkit;

/** Production AgentScope Harness factory with bounded workspace and sandbox observation. */
public final class ConfiguredAgentScopeHarnessFactory implements AgentScopeHarnessFactory {
    private static final Set<String> SANDBOX_METADATA = Set.of("sandboxId", "providerSandboxId", "profile",
            "status", "endpointRef", "expiresAt", "ownerTaskId", "ownerAttemptId");
    private final Model model;
    private final Path workspaceRoot;
    private final AgentScopeWorkspaceFactory workspaceFactory;
    private final Map<UUID, AgentScopeWorkspaceFactory.WorkspaceBinding> bindings =
            new ConcurrentHashMap<>();
    private final AtomicReference<Path> activeSkillRoot = new AtomicReference<>();
    private final AtomicReference<List<RuntimeMcpServer>> activeMcpServers =
            new AtomicReference<>(List.of());
    private final AtomicReference<Map<String, SkillCapabilityPolicy>> activeSkillCapabilities =
            new AtomicReference<>(Map.of());
    private final McpRuntimePort mcpRuntime;

    public ConfiguredAgentScopeHarnessFactory(String modelId, Path workspaceRoot) {
        this(resolveModel(modelId), workspaceRoot, unavailableProbe());
    }

    public ConfiguredAgentScopeHarnessFactory(String modelId, Path workspaceRoot,
            io.agentteams.worker.SandboxStateProbePort sandboxStateProbe) {
        this(resolveModel(modelId), workspaceRoot, sandboxStateProbe);
    }

    public ConfiguredAgentScopeHarnessFactory(Model model, Path workspaceRoot) {
        this(model, workspaceRoot, unavailableProbe());
    }

    /** Preserves the original public constructor while delegating to controlled workspace binding. */
    public ConfiguredAgentScopeHarnessFactory(Model model, Path workspaceRoot,
            io.agentteams.worker.SandboxStateProbePort sandboxStateProbe) {
        this(model, AgentScopeWorkspaceFactory.testOnly(sandboxStateProbe,
                java.time.Clock.systemUTC(), ensureRoot(workspaceRoot)), workspaceRoot);
    }

    public ConfiguredAgentScopeHarnessFactory(String modelId,
            AgentScopeWorkspaceFactory workspaceFactory, Path workspaceRoot) {
        this(resolveModel(modelId), workspaceFactory, workspaceRoot);
    }

    public ConfiguredAgentScopeHarnessFactory(Model model, AgentScopeWorkspaceFactory workspaceFactory,
            Path workspaceRoot) {
        this(model, workspaceFactory, workspaceRoot,
                new AgentScopeMcpRuntimePort(new EnvironmentMcpCredentialProvider()));
    }

    ConfiguredAgentScopeHarnessFactory(Model model, AgentScopeWorkspaceFactory workspaceFactory,
            Path workspaceRoot, McpRuntimePort mcpRuntime) {
        this.model = Objects.requireNonNull(model, "model");
        this.workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory");
        this.workspaceRoot = normalizeRoot(workspaceRoot);
        this.mcpRuntime = Objects.requireNonNull(mcpRuntime, "mcpRuntime");
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
    public void release(UUID taskId) {
        bindings.remove(Objects.requireNonNull(taskId, "taskId"));
    }

    @Override
    public void releaseAll() {
        bindings.clear();
    }

    @Override
    public void applyConfig(RuntimeConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Path root = null;
        for (Path directory : snapshot.skillDirectories().values()) {
            Path normalized = directory.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalized) || Files.isSymbolicLink(normalized)) {
                throw new IllegalArgumentException("activated Skill directory is unavailable");
            }
            Path parent = normalized.getParent();
            if (root == null) root = parent;
            else if (!root.equals(parent)) throw new IllegalArgumentException("Skill directories must share one runtime root");
        }
        activeSkillRoot.set(root);
        activeMcpServers.set(snapshot.mcpServers().values().stream().toList());
        activeSkillCapabilities.set(snapshot.skillCapabilities());
    }

    int bindingCount() {
        return bindings.size();
    }

    Map<String, SkillCapabilityPolicy> activeSkillCapabilities() {
        return activeSkillCapabilities.get();
    }

    @Override
    public HarnessAgent create(RuntimeTask task, AgentRuntimeContext context) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(context, "context");
        AgentScopeWorkspaceFactory.WorkspaceBinding binding = resolveBinding(task, context);
        if (binding.profile() != SandboxProfile.NONE && binding.workspacePath().isEmpty()) {
            throw new IllegalArgumentException("sandbox workspace must expose a verified local workspace");
        }
        bindings.put(task.id(), binding);
        Path workspace = binding.workspacePath().orElseGet(() -> workspaceRoot.resolve(task.id().toString()));
        workspace = workspace.toAbsolutePath().normalize();
        if (!workspace.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("resolved workspace is outside the configured root");
        }
        try {
            Files.createDirectories(workspace);
            String agentId = context.configuration().getOrDefault("worker_id",
                    task.metadata().getOrDefault("agentId", "agent-worker"));
            HarnessAgent.Builder builder = HarnessAgent.builder()
                    .name("agentteams-worker")
                    .agentId(agentId)
                    .defaultSessionId(required(task, "attemptId"))
                    .model(model)
                    .workspace(workspace)
                    .disableShellTool()
                    .disableSubagents()
                    .disableSessionPersistence()
                    .maxIters(10);
            List<RuntimeMcpServer> mcpServers = activeMcpServers.get();
            if (!mcpServers.isEmpty()) {
                Toolkit toolkit = new Toolkit();
                mcpRuntime.configure(toolkit, mcpServers, activeSkillCapabilities.get().values().stream().toList());
                builder.toolkit(toolkit);
            }
            Path skillRoot = activeSkillRoot.get();
            if (skillRoot != null) {
                builder.skillRepository(new FileSystemSkillRepository(skillRoot, false, "agentteams", true))
                        .disableDefaultWorkspaceSkills()
                        .disableDynamicSkills();
            }
            return builder.build();
        } catch (IOException error) {
            release(task.id());
            throw new IllegalStateException("unable to create AgentScope workspace", error);
        } catch (RuntimeException error) {
            release(task.id());
            throw error;
        }
    }

    private AgentScopeWorkspaceFactory.WorkspaceBinding resolveBinding(RuntimeTask task,
            AgentRuntimeContext context) {
        boolean hasSandboxMetadata = task.metadata().keySet().stream().anyMatch(SANDBOX_METADATA::contains);
        if (!hasWorkspaceScope(task)) {
            if (hasSandboxMetadata) {
                throw new IllegalArgumentException("sandbox metadata scope is incomplete");
            }
            return new AgentScopeWorkspaceFactory.WorkspaceBinding(
                    "agentscope-local-" + task.id(), SandboxProfile.NONE,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        String sandboxId = task.metadata().get("sandboxId");
        if (sandboxId == null || sandboxId.isBlank()) {
            if (hasSandboxMetadata) {
                throw new IllegalArgumentException("sandbox metadata is incomplete");
            }
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

    private static io.agentteams.worker.SandboxStateProbePort unavailableProbe() {
        return (sandboxId, taskId, attemptId) -> {
            throw new IllegalStateException("sandbox state probe is unavailable");
        };
    }

    private static Path ensureRoot(Path root) {
        Objects.requireNonNull(root, "workspaceRoot");
        try {
            Files.createDirectories(root);
            return root;
        } catch (IOException error) {
            throw new IllegalStateException("workspaceRoot cannot be created", error);
        }
    }

    private static Model resolveModel(String modelId) {
        if (modelId == null || modelId.isBlank() || "unknown".equalsIgnoreCase(modelId.trim())) {
            throw new IllegalArgumentException("AgentScope model must be configured");
        }
        return ModelRegistry.resolve(modelId.trim());
    }

    private static Path normalizeRoot(Path root) {
        Objects.requireNonNull(root, "workspaceRoot");
        Path normalized = root.toAbsolutePath().normalize();
        try {
            return normalized.toRealPath();
        } catch (IOException error) {
            throw new IllegalArgumentException("workspaceRoot cannot be verified", error);
        }
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
