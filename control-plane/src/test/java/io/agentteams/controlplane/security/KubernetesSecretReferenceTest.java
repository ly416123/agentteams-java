package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KubernetesSecretReferenceTest {

    @Test
    void parsesOnlyExplicitReferenceFormat() {
        assertThat(KubernetesSecretReference.parse("k8s://agentteams/qwen#api-key"))
                .isEqualTo(new KubernetesSecretReference("agentteams", "qwen", "api-key"));
        assertThat(KubernetesSecretReference.parse("secret/qwen")).isNull();
        assertThat(KubernetesSecretReference.parse("k8s://agentteams/qwen#api_key=plaintext")).isNull();
        assertThat(KubernetesSecretReference.parse("k8s://AgentTeams/qwen#api-key")).isNull();
    }
}
