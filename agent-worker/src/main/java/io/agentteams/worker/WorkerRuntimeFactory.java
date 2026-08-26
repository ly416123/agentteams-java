package io.agentteams.worker;

import io.agentscope.core.model.ModelRegistry;
import io.agentteams.runtime.AgentRuntime;
import io.agentteams.runtime.AgentScopeRolloutPolicy;
import io.agentteams.runtime.QwenPawHttpRuntimeConfiguration;
import io.agentteams.runtime.QwenPawHttpRuntimePort;
import io.agentteams.runtime.QwenPawRuntime;
import io.agentteams.runtime.ProjectScopedRuntimeModelCallAdmission;
import io.agentteams.runtime.RuntimeQuotaPort;
import io.agentteams.runtime.RuntimeModelCallAdmission;
import io.agentteams.runtime.SemaphoreRuntimeModelCallAdmission;
import io.agentteams.worker.agentscope.AgentScopeHarnessFactory;
import io.agentteams.worker.agentscope.AgentScopeRuntime;
import io.agentteams.worker.agentscope.ConfiguredAgentScopeHarnessFactory;
import io.grpc.ManagedChannel;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/** Composition root for QwenPaw, optional AgentScope, and the task owner router. */
public final class WorkerRuntimeFactory {
    private final AgentScopeHarnessFactory configuredHarness;
    private final AgentScopeRolloutPolicy rollout;

    public WorkerRuntimeFactory() {
        this(null, AgentScopeRolloutPolicy.fromEnvironment(System.getenv()));
    }

    public WorkerRuntimeFactory(AgentScopeHarnessFactory configuredHarness,
            AgentScopeRolloutPolicy rollout) {
        this.configuredHarness = configuredHarness;
        this.rollout = Objects.requireNonNull(rollout, "rollout");
    }

    public void validate(QwenPawWorker.WorkerConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (configuration.runtime() != io.agentteams.application.api.ExecutionRuntime.AGENTSCOPE) {
            return;
        }
        if (configuredHarness == null && !modelConfigured(configuration)) {
            throw new IllegalStateException("AGENTSCOPE runtime requires configured Harness and model");
        }
        if (configuredHarness == null && !ModelRegistry.canResolve(configuration.model())) {
            throw new IllegalStateException("AGENTSCOPE runtime requires configured Harness and model");
        }
    }

    public AgentRuntime create(QwenPawWorker.WorkerConfiguration configuration,
            ManagedChannel gatewayChannel, Clock clock, RuntimeQuotaPort quotaPort) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(gatewayChannel, "gatewayChannel");
        Objects.requireNonNull(clock, "clock");
        validate(configuration);
        AgentRuntime qwenPaw = qwenPaw(configuration, gatewayChannel, clock, quotaPort);
        AgentRuntime agentScope = agentScope(configuration);
        AgentScopeRolloutPolicy effectiveRollout = configuration.runtime()
                == io.agentteams.application.api.ExecutionRuntime.AGENTSCOPE
                ? new AgentScopeRolloutPolicy(AgentScopeRolloutPolicy.AGENTSCOPE, true, 100,
                        java.util.Set.of(), java.util.Set.of(), java.util.Set.of())
                : rollout;
        return new WorkerRuntimeRouter(qwenPaw, agentScope, effectiveRollout);
    }

    private AgentRuntime agentScope(QwenPawWorker.WorkerConfiguration configuration) {
        AgentScopeHarnessFactory harness = configuredHarness;
        if (harness == null && modelConfigured(configuration)
                && ModelRegistry.canResolve(configuration.model())) {
            harness = new ConfiguredAgentScopeHarnessFactory(configuration.model(), workspaceRoot());
        }
        if (harness == null) {
            return null;
        }
        return new AgentScopeRuntime(harness);
    }

    private static AgentRuntime qwenPaw(QwenPawWorker.WorkerConfiguration configuration,
            ManagedChannel gatewayChannel, Clock clock, RuntimeQuotaPort quotaPort) {
        RuntimeQuotaPort effectiveQuota = quotaPort == null
                ? QwenPawWorker.remoteQuotaPort(configuration, gatewayChannel, clock) : quotaPort;
        QwenPawHttpRuntimeConfiguration qwenConfiguration = new QwenPawHttpRuntimeConfiguration(
                URI.create(configuration.qwenPawEndpoint()), configuration.qwenPawAgentId(),
                configuration.qwenPawAuthorizationToken(), configuration.qwenPawConnectTimeout(),
                configuration.qwenPawUserId(), configuration.qwenPawChannel(),
                configuration.qwenPawConfigurationPath());
        RuntimeModelCallAdmission local = new SemaphoreRuntimeModelCallAdmission(
                configuration.modelCallMaxConcurrent());
        RuntimeModelCallAdmission admission = effectiveQuota == null
                ? local : new ProjectScopedRuntimeModelCallAdmission(effectiveQuota, local);
        return new QwenPawRuntime(new QwenPawHttpRuntimePort(qwenConfiguration), admission);
    }

    private static boolean modelConfigured(QwenPawWorker.WorkerConfiguration configuration) {
        return configuration.model() != null && !configuration.model().isBlank()
                && !"unknown".equalsIgnoreCase(configuration.model().trim());
    }

    private static Path workspaceRoot() {
        String configured = System.getenv("AGENTSCOPE_WORKSPACE");
        return Path.of(configured == null || configured.isBlank()
                ? "/tmp/agentteams-agentscope" : configured.trim());
    }
}
