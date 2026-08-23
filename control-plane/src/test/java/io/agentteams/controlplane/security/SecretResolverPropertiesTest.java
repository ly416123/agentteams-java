package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecretResolverPropertiesTest {

    @Test
    void defaultsToValidationOnly() {
        SecretResolverProperties properties = new SecretResolverProperties();

        org.assertj.core.api.Assertions.assertThat(properties.getBackend())
                .isEqualTo(SecretResolverProperties.Backend.VALIDATION_ONLY);
        org.assertj.core.api.Assertions.assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void KubernetesBackendRequiresEveryAllowlist() {
        SecretResolverProperties properties = new SecretResolverProperties();

        assertThatThrownBy(properties::validateKubernetes)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespaces allowlist");
        properties.setAllowedNamespaces(List.of("agentteams"));
        properties.setAllowedNames(List.of("qwen"));
        properties.setAllowedKeys(List.of("api-key"));
        properties.setTimeout(Duration.ofSeconds(1));
        properties.validateKubernetes();
    }
}
