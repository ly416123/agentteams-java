package io.agentteams.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.runtime.AgentScopeRolloutPolicy;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkerRuntimeFactoryTest {
    @Test
    void explicitlyConfiguredAgentScopeFailsFastWhenHarnessOrModelIsMissing() {
        QwenPawWorker.WorkerConfiguration configuration = QwenPawWorker.WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", "agent-a",
                "AGENTTEAMS_RUNTIME", "AGENTSCOPE"));

        assertThatThrownBy(() -> new WorkerRuntimeFactory().validate(configuration))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AGENTSCOPE runtime requires configured Harness and model");
    }

    @Test
    void requiresAInjectedSandboxStateProbeForConfiguredAgentScopeWiring() {
        SandboxStateProbePort probe = (sandboxId, taskId, attemptId) -> null;

        WorkerRuntimeFactory factory = new WorkerRuntimeFactory(null,
                new AgentScopeRolloutPolicy("QWENPAW", false, 0,
                        Set.of(), Set.of(), Set.of()), probe);

        org.assertj.core.api.Assertions.assertThat(factory.sandboxStateProbe()).isSameAs(probe);
    }
}
