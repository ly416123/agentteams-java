package io.agentteams.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerStableSpecTest {
    @Test
    void parsesOnlyACompleteSpecForTheCurrentAgent() {
        WorkerSpec spec = WorkerStableSpec.parse("""
                {"agentId":"agent-1","runtime":"qwenpaw","image":"example/worker@sha256:old",
                 "replicas":2,"env":{"MODEL":"stable"},"tlsSecret":""}
                """, "agent-1");

        assertThat(spec.image()).isEqualTo("example/worker@sha256:old");
        assertThat(spec.env()).isEqualTo(Map.of("MODEL", "stable"));
    }

    @Test
    void rejectsIncompleteOrCrossAgentSnapshots() {
        assertThatThrownBy(() -> WorkerStableSpec.parse("{\"image\":\"old\"}", "agent-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkerStableSpec.parse(
                "{\"agentId\":\"agent-2\",\"runtime\":\"qwenpaw\",\"image\":\"old\",\"replicas\":1}",
                "agent-1")).isInstanceOf(IllegalArgumentException.class);
    }
}
