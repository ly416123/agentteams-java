package io.agentteams.worker.agentscope;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ConfiguredAgentScopeHarnessFactoryTest {
    @Test
    void rejectsMissingModelConfigurationBeforeCreatingAWorkerSession() {
        assertThatThrownBy(() -> new ConfiguredAgentScopeHarnessFactory("", Path.of("/tmp/worker")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AgentScope model must be configured");
    }
}
