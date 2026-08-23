package io.agentteams.controlplane.security;

import io.fabric8.kubernetes.client.KubernetesClient;

/** Creates a deployment-selected resolver without changing the resolver SPI. */
public final class SecretResolverFactory {
    private SecretResolverFactory() {
    }

    public static SecretResolver create(SecretResolverProperties properties, KubernetesClient kubernetesClient) {
        java.util.Objects.requireNonNull(properties, "properties");
        SecretResolverProperties.Backend backend = properties.getBackend();
        if (backend == null || backend == SecretResolverProperties.Backend.VALIDATION_ONLY) {
            return new ValidationOnlySecretResolver();
        }
        if (backend == SecretResolverProperties.Backend.EXTERNAL_SECRETS) {
            return new ExternalSecretsSecretResolver();
        }
        if (kubernetesClient == null) {
            throw new IllegalArgumentException("Kubernetes SecretResolver requires a KubernetesClient");
        }
        return new KubernetesSecretResolver(kubernetesClient, properties);
    }
}
