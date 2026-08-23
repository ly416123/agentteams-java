package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CredentialReferenceValidatorTest {
    @Test
    void acceptsSecretReferencesAndRejectsInlineMaterial() {
        assertThat(CredentialReferenceValidator.normalize("secret/deepseek")).isEqualTo("secret/deepseek");
        assertThat(CredentialReferenceValidator.normalize("k8s://agentteams/deepseek")).isEqualTo("k8s://agentteams/deepseek");
        assertThat(CredentialReferenceValidator.normalize("k8s://agentteams/deepseek#api-key"))
                .isEqualTo("k8s://agentteams/deepseek#api-key");
        assertThatThrownBy(() -> CredentialReferenceValidator.normalize("password=secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CredentialReferenceValidator.normalize("sk-plain-token"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
