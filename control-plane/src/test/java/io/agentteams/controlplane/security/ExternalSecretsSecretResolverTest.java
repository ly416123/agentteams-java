package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ExternalSecretsSecretResolverTest {

    @Test
    void isAnExplicitNoNetworkBoundaryUntilDeploymentAdapterExists() {
        SecretResolver resolver = new ExternalSecretsSecretResolver();

        assertThat(resolver.resolve("secret/qwen").status()).isEqualTo(SecretResolver.Status.UNAVAILABLE);
        assertThat(resolver.resolve("password=plaintext").status())
                .isEqualTo(SecretResolver.Status.INVALID_REFERENCE);
    }

    @Test
    void resolvesOnlyWhenExternalSecretIsReadyAndTargetKeyExists() {
        ExternalSecretStatusReader statusReader = (namespace, name) ->
                new ExternalSecretStatus(ExternalSecretStatus.State.READY, "provider-secret", "generation-2");
        KubernetesSecretMetadataReader metadataReader = (namespace, name) ->
                new KubernetesSecretMetadataReader.Metadata(Set.of("password"), "resource-7", "2");
        SecretResolver resolver = new ExternalSecretsSecretResolver(statusReader, metadataReader);

        assertThat(resolver.resolve("externalsecret://agentteams/provider#password").status())
                .isEqualTo(SecretResolver.Status.RESOLVED);
    }

    @Test
    void keepsCredentialUnavailableWhenExternalSecretIsNotReady() {
        ExternalSecretStatusReader statusReader = (namespace, name) ->
                new ExternalSecretStatus(ExternalSecretStatus.State.NOT_READY, "provider-secret", "generation-2");
        KubernetesSecretMetadataReader metadataReader = (namespace, name) ->
                new KubernetesSecretMetadataReader.Metadata(Set.of("password"), "resource-7", "2");
        SecretResolver resolver = new ExternalSecretsSecretResolver(statusReader, metadataReader);

        assertThat(resolver.resolve("externalsecret://agentteams/provider#password").status())
                .isEqualTo(SecretResolver.Status.UNAVAILABLE);
    }

    @Test
    void reportsMissingWhenReadyTargetDoesNotContainRequestedKey() {
        ExternalSecretStatusReader statusReader = (namespace, name) ->
                new ExternalSecretStatus(ExternalSecretStatus.State.READY, "provider-secret", "generation-2");
        KubernetesSecretMetadataReader metadataReader = (namespace, name) ->
                new KubernetesSecretMetadataReader.Metadata(Set.of("username"), "resource-7", "2");
        SecretResolver resolver = new ExternalSecretsSecretResolver(statusReader, metadataReader);

        assertThat(resolver.resolve("externalsecret://agentteams/provider#password").status())
                .isEqualTo(SecretResolver.Status.MISSING);
    }

    @Test
    void keepsCredentialUnavailableWhenExternalSecretGenerationIsStale() {
        ExternalSecretStatus status = new ExternalSecretStatus(
                ExternalSecretStatus.State.READY, "provider-secret", "2", "1");

        assertThat(status.generationIsCurrent()).isFalse();
        SecretResolver resolver = new ExternalSecretsSecretResolver(
                (namespace, name) -> status,
                (namespace, name) -> new KubernetesSecretMetadataReader.Metadata(
                        Set.of("password"), "resource-7", "2"));

        assertThat(resolver.resolve("externalsecret://agentteams/provider#password").status())
                .isEqualTo(SecretResolver.Status.UNAVAILABLE);
    }
}
