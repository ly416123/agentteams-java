package io.agentteams.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
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
}
