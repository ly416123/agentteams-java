package io.agentteams.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpTransportConnectorRegistryTest {

    @Test
    void selectsConcreteHttpConnectorForBothTransportsAndKeepsValidationAsFallback() {
        McpTransportConnector validation = new ValidationOnlyMcpTransportConnector();
        McpTransportConnector http = new McpHttpTransportConnector(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                new McpHttpConnectorProperties());

        McpTransportConnectorRegistry registry = new McpTransportConnectorRegistry(List.of(validation, http));
        McpTransportConnectorRegistry reverseOrder = new McpTransportConnectorRegistry(List.of(http, validation));

        assertThat(registry.select(McpTransport.SSE)).isSameAs(http);
        assertThat(registry.select(McpTransport.STREAMABLE_HTTP)).isSameAs(http);
        assertThat(reverseOrder.select(McpTransport.SSE)).isSameAs(http);
        assertThat(reverseOrder.select(McpTransport.STREAMABLE_HTTP)).isSameAs(http);
    }

    @Test
    void validationConnectorRemainsDefaultForBothTransports() {
        McpTransportConnector validation = connector(McpTransport.SSE, Set.of(McpTransport.SSE,
                McpTransport.STREAMABLE_HTTP), true);
        McpTransportConnectorRegistry registry = new McpTransportConnectorRegistry(List.of(validation));

        assertThat(registry.select(McpTransport.SSE)).isSameAs(validation);
        assertThat(registry.select(McpTransport.STREAMABLE_HTTP)).isSameAs(validation);
    }

    @Test
    void unsupportedTransportReturnsNoConnector() {
        McpTransportConnectorRegistry registry = new McpTransportConnectorRegistry();

        assertThat(registry.select(McpTransport.SSE)).isNull();
        assertThat(registry.supports(McpTransport.STREAMABLE_HTTP)).isFalse();
    }

    @Test
    void duplicateRegistrationForOneTransportIsRejected() {
        McpTransportConnector first = connector(McpTransport.SSE, Set.of(McpTransport.SSE), false);
        McpTransportConnector second = connector(McpTransport.SSE, Set.of(McpTransport.SSE), false);
        McpTransportConnectorRegistry registry = new McpTransportConnectorRegistry(List.of(first));

        assertThatThrownBy(() -> registry.register(second))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate MCP connector registration")
                .hasMessageContaining("SSE");
    }

    private static McpTransportConnector connector(McpTransport declaredTransport,
            Set<McpTransport> supportedTransports, boolean fallback) {
        return new McpTransportConnector() {
            @Override
            public McpTransport transport() {
                return declaredTransport;
            }

            @Override
            public boolean supports(McpTransport transport) {
                return supportedTransports.contains(transport);
            }

            @Override
            public boolean isFallback() {
                return fallback;
            }

            @Override
            public List<McpToolDescriptor> discoverTools(McpConnectorTarget target, Duration timeout) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object callTool(McpConnectorTarget target, String toolName, Map<String, Object> arguments,
                    Duration timeout) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
