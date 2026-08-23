package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretResolverFactoryTest {

    @Test
    void keepsValidationOnlyAsDefaultAndExternalSecretsAsUnavailableBoundary() {
        SecretResolverProperties defaults = new SecretResolverProperties();
        assertThat(SecretResolverFactory.create(defaults, null))
                .isInstanceOf(ValidationOnlySecretResolver.class);

        defaults.setBackend(SecretResolverProperties.Backend.EXTERNAL_SECRETS);
        assertThat(SecretResolverFactory.create(defaults, null))
                .isInstanceOf(ExternalSecretsSecretResolver.class);
    }
}
