package io.agentteams.controlplane.service;

import io.agentteams.controlplane.security.SecretResolver;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Opt-in wiring for the real endpoint probe; validation-only remains default. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelProviderConnectionProbeProperties.class)
@ConditionalOnProperty(name = "agentteams.model-provider.connection-probe.enabled", havingValue = "true")
public class ModelProviderConnectionProbeConfiguration {

    @Bean
    HttpClient modelProviderConnectionProbeHttpClient(ModelProviderConnectionProbeProperties properties) {
        properties.validate();
        return HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    @Primary
    HttpModelProviderConnectionProbe httpModelProviderConnectionProbe(SecretResolver secretResolver,
            HttpClient modelProviderConnectionProbeHttpClient, ModelProviderConnectionProbeProperties properties) {
        return new HttpModelProviderConnectionProbe(secretResolver, modelProviderConnectionProbeHttpClient, properties);
    }
}
