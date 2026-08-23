package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ValidationOnlySecretResolverTest {

    private final SecretResolver resolver = new ValidationOnlySecretResolver();

    @Test
    void reportsValidationOnlyWithoutReturningSecretMaterial() {
        SecretResolver.Resolution result = resolver.resolve("secret/deepseek");

        assertThat(result.status()).isEqualTo(SecretResolver.Status.VALIDATION_ONLY);
    }

    @Test
    void rejectsMissingAndInlineCredentialValues() {
        assertThat(resolver.resolve(null).status()).isEqualTo(SecretResolver.Status.MISSING);
        assertThat(resolver.resolve("sk-plain-token").status())
                .isEqualTo(SecretResolver.Status.INVALID_REFERENCE);
        assertThat(resolver.resolve("password=secret").status())
                .isEqualTo(SecretResolver.Status.INVALID_REFERENCE);
    }
}
