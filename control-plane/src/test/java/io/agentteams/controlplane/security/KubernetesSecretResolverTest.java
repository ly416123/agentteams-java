package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class KubernetesSecretResolverTest {

    @Test
    void resolvesPresenceWithoutReturningSecretMaterial() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (KubernetesSecretResolver resolver = new KubernetesSecretResolver(
                (namespace, name, key) -> KubernetesSecretReader.ValueState.PRESENT,
                properties(), executor)) {
            assertThat(resolver.resolve("k8s://agentteams/qwen#api-key").status())
                    .isEqualTo(SecretResolver.Status.RESOLVED);
        }
    }

    @Test
    void rejectsNonAllowlistedReferencesBeforeReading() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (KubernetesSecretResolver resolver = new KubernetesSecretResolver(
                (namespace, name, key) -> { throw new AssertionError("reader must not be called"); },
                properties(), executor)) {
            assertThat(resolver.resolve("k8s://other/qwen#api-key").status())
                    .isEqualTo(SecretResolver.Status.INVALID_REFERENCE);
            assertThat(resolver.resolve("secret/qwen").status())
                    .isEqualTo(SecretResolver.Status.INVALID_REFERENCE);
        }
    }

    @Test
    void classifiesMissingAndTimeoutWithoutLeakingExceptions() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (KubernetesSecretResolver missing = new KubernetesSecretResolver(
                (namespace, name, key) -> KubernetesSecretReader.ValueState.MISSING,
                properties(), executor)) {
            assertThat(missing.resolve("k8s://agentteams/qwen#api-key").status())
                    .isEqualTo(SecretResolver.Status.MISSING);
        }

        ExecutorService timeoutExecutor = Executors.newSingleThreadExecutor();
        try (KubernetesSecretResolver timeout = new KubernetesSecretResolver(
                (namespace, name, key) -> {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return KubernetesSecretReader.ValueState.PRESENT;
                }, timeoutProperties(), timeoutExecutor)) {
            assertThat(timeout.resolve("k8s://agentteams/qwen#api-key").status())
                    .isEqualTo(SecretResolver.Status.UNAVAILABLE);
        }
    }

    @Test
    void requiresStrictAllowlists() {
        assertThatThrownBy(() -> new KubernetesSecretResolver(
                (namespace, name, key) -> KubernetesSecretReader.ValueState.PRESENT,
                new SecretResolverProperties(), Executors.newSingleThreadExecutor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlist");
    }

    private static SecretResolverProperties properties() {
        SecretResolverProperties properties = new SecretResolverProperties();
        properties.setAllowedNamespaces(List.of("agentteams"));
        properties.setAllowedNames(List.of("qwen"));
        properties.setAllowedKeys(List.of("api-key"));
        properties.setTimeout(Duration.ofSeconds(1));
        return properties;
    }

    private static SecretResolverProperties timeoutProperties() {
        SecretResolverProperties properties = properties();
        properties.setTimeout(Duration.ofMillis(20));
        return properties;
    }
}
