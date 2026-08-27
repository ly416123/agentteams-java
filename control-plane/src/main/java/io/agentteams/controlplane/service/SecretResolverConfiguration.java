package io.agentteams.controlplane.service;

import io.agentteams.controlplane.security.ExternalSecretsSecretResolver;
import io.agentteams.controlplane.security.ExternalSecretStatusReader;
import io.agentteams.controlplane.security.KubernetesSecretMetadataReader;
import io.agentteams.controlplane.security.KubernetesSecretResolver;
import io.agentteams.controlplane.security.SecretResolverProperties;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Opt-in resolver wiring. With no backend property the existing validation-only
 * bean remains the effective default.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecretResolverProperties.class)
public class SecretResolverConfiguration {

    @Bean(name = "secretResolverKubernetesClient", destroyMethod = "close")
    @ConditionalOnProperty(name = "agentteams.security.secret-resolver.backend", havingValue = "kubernetes")
    KubernetesClient secretResolverKubernetesClient() {
        return new KubernetesClientBuilder().build();
    }

    @Bean(name = "externalSecretsKubernetesClient", destroyMethod = "close")
    @ConditionalOnProperty(name = "agentteams.security.secret-resolver.backend", havingValue = "external-secrets")
    KubernetesClient externalSecretsKubernetesClient() {
        return new KubernetesClientBuilder().build();
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "agentteams.security.secret-resolver.backend", havingValue = "kubernetes")
    KubernetesSecretResolver kubernetesSecretResolver(SecretResolverProperties properties,
            @Qualifier("secretResolverKubernetesClient") KubernetesClient client) {
        return new KubernetesSecretResolver(client, properties);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "agentteams.security.secret-resolver.backend", havingValue = "external-secrets")
    ExternalSecretsSecretResolver externalSecretsSecretResolver(
            @Qualifier("externalSecretsKubernetesClient") KubernetesClient client) {
        return new ExternalSecretsSecretResolver(ExternalSecretStatusReader.kubernetes(client),
                KubernetesSecretMetadataReader.kubernetes(client));
    }
}
