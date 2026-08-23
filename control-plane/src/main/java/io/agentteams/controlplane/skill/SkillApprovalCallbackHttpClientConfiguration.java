package io.agentteams.controlplane.skill;

import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit opt-in wiring; absent or false configuration keeps Skill approval fail-closed. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SkillApprovalCallbackHttpClientProperties.class)
@ConditionalOnProperty(name = "agentteams.skill.approval-callback.http.enabled", havingValue = "true")
public class SkillApprovalCallbackHttpClientConfiguration {

    @Bean("skillApprovalCallbackHttpClient")
    HttpClient skillApprovalCallbackHttpClient(SkillApprovalCallbackHttpClientProperties properties) {
        properties.validate();
        return HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    SkillScanApprovalPort skillScanApprovalPort(
            @Qualifier("skillApprovalCallbackHttpClient") HttpClient httpClient,
            SkillApprovalCallbackHttpClientProperties properties) {
        return new HttpSkillApprovalCallbackClient(httpClient, properties.getEndpoint(),
                properties.getRequestTimeout(), properties.getMaxResponseBytes());
    }
}
