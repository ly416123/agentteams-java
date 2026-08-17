package io.agentteams.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRuntimeRegistryTest {
    @Test
    void resolvesConfiguredDefaultRuntime() {
        AgentRuntime runtime = new FakeRuntime();
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry("qwenpaw", Map.of("qwenpaw", runtime));

        assertThat(registry.defaultRuntime()).isSameAs(runtime);
    }

    @Test
    void requiresDefaultRuntimeToBeRegistered() {
        assertThatThrownBy(() -> new AgentRuntimeRegistry("missing", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not registered");
    }
}
