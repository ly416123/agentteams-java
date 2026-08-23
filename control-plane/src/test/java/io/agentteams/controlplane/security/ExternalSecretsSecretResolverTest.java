package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExternalSecretsSecretResolverTest {

    @Test
    void isAnExplicitNoNetworkBoundaryUntilDeploymentAdapterExists() {
        SecretResolver resolver = new ExternalSecretsSecretResolver();

        assertThat(resolver.resolve("secret/qwen").status()).isEqualTo(SecretResolver.Status.UNAVAILABLE);
        assertThat(resolver.resolve("password=plaintext").status())
                .isEqualTo(SecretResolver.Status.INVALID_REFERENCE);
    }
}
