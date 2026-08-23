package io.agentteams.controlplane.skill;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit opt-in wiring; the default application context creates no sandbox HTTP client. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SkillSandboxHttpClientProperties.class)
@ConditionalOnProperty(name = {
        "agentteams.skill.security-scanner.external.enabled",
        "agentteams.skill.security-scanner.http.enabled"
}, havingValue = "true")
public class SkillSandboxHttpClientConfiguration {

    @Bean
    HttpClient skillSandboxHttpClient(SkillSandboxHttpClientProperties properties) {
        properties.validate();
        return HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    SkillSandboxScannerClient skillSandboxScannerClient(HttpClient skillSandboxHttpClient,
            SkillSandboxHttpClientProperties properties) {
        return new HttpSkillSandboxScannerClient(skillSandboxHttpClient, properties.getEndpoint(),
                properties.getRequestTimeout(), properties.getMaxResponseBytes());
    }
}
