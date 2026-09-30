package io.agentteams.worker;

import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentteams.runtime.AgentRuntime;
import io.agentteams.runtime.RuntimeQuotaPort;
import io.agentteams.worker.agentscope.AgentScopeHarnessFactory;
import io.agentteams.worker.agentscope.AgentScopeRuntime;
import io.agentteams.worker.agentscope.ConfiguredAgentScopeHarnessFactory;
import io.agentteams.worker.agentscope.AgentScopeWorkspaceFactory;
import io.agentteams.worker.agentscope.SandboxWorkspaceOwnershipPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/** Composition root for the single AgentScope Harness execution runtime. */
public final class WorkerRuntimeFactory {
    private final AgentScopeHarnessFactory configuredHarness;
    private final SandboxStateProbePort sandboxStateProbe;
    private final SandboxWorkspaceOwnershipPort workspaceOwnership;

    public WorkerRuntimeFactory() {
        this(null, (sandboxId, taskId, attemptId) -> {
                    throw new IllegalStateException("sandbox state probe is unavailable");
                }, new FailClosedWorkspaceOwnershipPort());
    }

    public WorkerRuntimeFactory(AgentScopeHarnessFactory configuredHarness,
            SandboxStateProbePort sandboxStateProbe) {
        this(configuredHarness, sandboxStateProbe, new FailClosedWorkspaceOwnershipPort());
    }

    public WorkerRuntimeFactory(AgentScopeHarnessFactory configuredHarness,
            SandboxStateProbePort sandboxStateProbe, SandboxWorkspaceOwnershipPort workspaceOwnership) {
        this.configuredHarness = configuredHarness;
        this.sandboxStateProbe = Objects.requireNonNull(sandboxStateProbe, "sandboxStateProbe");
        this.workspaceOwnership = Objects.requireNonNull(workspaceOwnership, "workspaceOwnership");
    }

    public WorkerRuntimeFactory(SandboxStateProbePort sandboxStateProbe) {
        this(null, sandboxStateProbe, new FailClosedWorkspaceOwnershipPort());
    }

    SandboxStateProbePort sandboxStateProbe() { return sandboxStateProbe; }

    public void validate(AgentTeamsWorker.WorkerConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (configuredHarness == null && !modelConfigured(configuration)) {
            throw new IllegalStateException("AGENTSCOPE runtime requires configured Harness and model");
        }
        if (configuredHarness == null && !ModelRegistry.canResolve(configuration.model())) {
            throw new IllegalStateException("AGENTSCOPE model provider cannot resolve: "
                    + configuration.model());
        }
    }

    public AgentRuntime create(AgentTeamsWorker.WorkerConfiguration configuration,
            io.grpc.ManagedChannel gatewayChannel, Clock clock, RuntimeQuotaPort quotaPort) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(gatewayChannel, "gatewayChannel");
        Objects.requireNonNull(clock, "clock");
        validate(configuration);
        AgentRuntime agentScope = agentScope(configuration, clock);
        if (agentScope == null) {
            throw new IllegalStateException("AgentScope Harness is unavailable");
        }
        return agentScope;
    }

    private AgentRuntime agentScope(AgentTeamsWorker.WorkerConfiguration configuration, Clock clock) {
        AgentScopeHarnessFactory harness = configuredHarness;
        ConfiguredAgentScopeHarnessFactory configured = null;
        if (harness == null && modelConfigured(configuration)
                && ModelRegistry.canResolve(configuration.model())) {
            Path root = workspaceRoot();
            try {
                Files.createDirectories(root);
            } catch (java.io.IOException error) {
                throw new IllegalStateException("AgentScope workspace root cannot be created", error);
            }
            AgentScopeWorkspaceFactory workspaceFactory = new AgentScopeWorkspaceFactory(
                    sandboxStateProbe, clock, root, workspaceOwnership);
            configured = new ConfiguredAgentScopeHarnessFactory(
                    ModelRegistry.resolve(configuration.model(), modelCreationContext(System.getenv())),
                    workspaceFactory, root);
            harness = configured;
        }
        if (harness == null) {
            return null;
        }
        return configured == null ? new AgentScopeRuntime(harness)
                : new AgentScopeRuntime(harness, ignored -> { }, configured.activeGuard());
    }

    private static boolean modelConfigured(AgentTeamsWorker.WorkerConfiguration configuration) {
        return configuration.model() != null && !configuration.model().isBlank()
                && !"unknown".equalsIgnoreCase(configuration.model().trim());
    }

    static ModelCreationContext modelCreationContext(Map<String, String> environment) {
        String baseUrl = environment.get("AGENTTEAMS_MODEL_BASE_URL");
        ModelCreationContext.Builder builder = ModelCreationContext.builder();
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl.trim());
        }
        String proxyHost = environment.get("AGENTTEAMS_MODEL_PROXY_HOST");
        String proxyPort = environment.get("AGENTTEAMS_MODEL_PROXY_PORT");
        if ((proxyHost != null && !proxyHost.isBlank()) || (proxyPort != null && !proxyPort.isBlank())) {
            if (proxyHost == null || proxyHost.isBlank()) {
                throw new IllegalArgumentException(
                        "AGENTTEAMS_MODEL_PROXY_HOST must be set when model proxy is enabled");
            }
            int port;
            try {
                port = Integer.parseInt(proxyPort == null ? "" : proxyPort.trim());
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("AGENTTEAMS_MODEL_PROXY_PORT must be a valid port", error);
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("AGENTTEAMS_MODEL_PROXY_PORT must be 1..65535");
            }
            builder.component(ProxyConfig.class, ProxyConfig.http(proxyHost.trim(), port));
        }
        return builder.build();
    }

    private static Path workspaceRoot() {
        String configured = System.getenv("AGENTSCOPE_WORKSPACE");
        return Path.of(configured == null || configured.isBlank()
                ? "/tmp/agentteams-agentscope" : configured.trim());
    }

    private static final class FailClosedWorkspaceOwnershipPort implements SandboxWorkspaceOwnershipPort {
        @Override public boolean durable() { return true; }
        @Override public java.util.Optional<WorkspaceOwner> findSandboxOwner(String providerSandboxId) {
            return java.util.Optional.empty();
        }
        @Override public java.util.Optional<WorkspaceOwner> findWorkspaceOwner(Path workspacePath) {
            return java.util.Optional.empty();
        }
        @Override public void claimSandbox(String providerSandboxId, WorkspaceOwner owner) {
            throw new IllegalStateException("sandbox workspace ownership is unavailable");
        }
        @Override public void claimWorkspace(Path workspacePath, WorkspaceOwner owner) {
            throw new IllegalStateException("sandbox workspace ownership is unavailable");
        }
    }
}
