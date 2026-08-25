package io.agentteams.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentScopeRolloutPolicyTest {
    @Test
    void remainsOnQwenPawWhenDisabledAndUsesStableAllowlist() {
        AgentScopeRolloutPolicy disabled = new AgentScopeRolloutPolicy("QWENPAW", false, 100,
                Set.of("agent-a"), Set.of(), Set.of());
        assertThat(disabled.select(Map.of("agentId", "agent-a"))).isEqualTo("QWENPAW");

        AgentScopeRolloutPolicy enabled = new AgentScopeRolloutPolicy("QWENPAW", true, 0,
                Set.of("agent-a"), Set.of(), Set.of());
        assertThat(enabled.select(Map.of("agentId", "agent-a"))).isEqualTo("AGENTSCOPE");
        assertThat(enabled.select(Map.of("agentId", "agent-b"))).isEqualTo("QWENPAW");
    }

    @Test
    void percentageDecisionIsDeterministicAndMissingScopeFailsClosed() {
        AgentScopeRolloutPolicy policy = new AgentScopeRolloutPolicy("QWENPAW", true, 50,
                Set.of(), Set.of(), Set.of());
        String first = policy.select(Map.of("agentId", "agent-stable"));
        assertThat(policy.select(Map.of("agentId", "agent-stable"))).isEqualTo(first);
        assertThat(policy.select(Map.of())).isEqualTo("QWENPAW");
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new AgentScopeRolloutPolicy("UNKNOWN", false, 0,
                Set.of(), Set.of(), Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentScopeRolloutPolicy("QWENPAW", true, 101,
                Set.of(), Set.of(), Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentScopeRolloutPolicy.fromEnvironment(Map.of(
                "AGENTTEAMS_AGENTSCOPE_ROLLOUT_PERCENTAGE", "nope")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
