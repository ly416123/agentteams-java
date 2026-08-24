package io.agentteams.controlplane.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DashboardAlertNotificationProperties.class)
public class DashboardAlertNotificationConfiguration {
    @Bean
    DashboardAlertNotificationPort dashboardAlertNotificationPort(
            DashboardAlertNotificationProperties properties, ObjectMapper objectMapper) {
        if (!properties.isEnabled() || properties.getWebhookUrl() == null) {
            return new LoggingDashboardAlertNotificationPort();
        }
        return new WebhookDashboardAlertNotificationPort(HttpClient.newHttpClient(), objectMapper,
                properties.getWebhookUrl(), properties.getTimeout());
    }
}
