package io.agentteams.controlplane.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Opt-in wiring for the real, redirect-free MCP HTTP connector. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpHttpConnectorProperties.class)
@ConditionalOnProperty(name = "agentteams.mcp.http.enabled", havingValue = "true")
public class McpHttpConnectorConfiguration {

    @Bean
    HttpClient mcpHttpConnectorHttpClient(McpHttpConnectorProperties properties) {
        properties.validate();
        return HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    McpHttpTransportConnector mcpHttpTransportConnector(HttpClient mcpHttpConnectorHttpClient,
            McpHttpConnectorProperties properties, ObjectMapper objectMapper) {
        return new McpHttpTransportConnector(mcpHttpConnectorHttpClient, properties, objectMapper);
    }
}
