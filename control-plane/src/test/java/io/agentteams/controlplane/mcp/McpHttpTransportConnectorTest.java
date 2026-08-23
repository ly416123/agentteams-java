package io.agentteams.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class McpHttpTransportConnectorTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void discoversToolsAndDoesNotSendCredentialHeadersOrReferences() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        server = server(exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            respond(exchange, 200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":1,"
                    + "\"result\":{\"tools\":[{\"name\":\"search\",\"description\":\"Search\","
                    + "\"inputSchema\":{\"type\":\"object\"}}]}}");
        });

        McpHttpTransportConnector connector = connector();
        List<McpToolDescriptor> tools = connector.discoverTools(target(McpTransport.STREAMABLE_HTTP),
                Duration.ofSeconds(1));

        assertThat(tools).extracting(McpToolDescriptor::name).containsExactly("search");
        assertThat(request).hasValueSatisfying(body -> assertThat(body)
                .contains("tools/list").doesNotContain("credentialRef", "secret"));
    }

    @Test
    void endpointAllowlistIsFailClosed() throws Exception {
        server = server(exchange -> respond(exchange, 200, "application/json", "{}"));
        McpHttpConnectorProperties properties = properties();
        properties.setAllowedHosts(List.of("other.example"));
        McpHttpTransportConnector connector = new McpHttpTransportConnector(client(), properties);

        assertThatThrownBy(() -> connector.discoverTools(target(McpTransport.STREAMABLE_HTTP), Duration.ofSeconds(1)))
                .isInstanceOf(McpHttpConnectorException.class)
                .extracting(error -> ((McpHttpConnectorException) error).category())
                .isEqualTo(McpHttpFailureCategory.ENDPOINT_NOT_ALLOWED);
    }

    @Test
    void statusFailuresAreClassifiedWithoutIncludingResponseBody() throws Exception {
        server = server(exchange -> respond(exchange, 429, "text/plain", "secret upstream detail"));

        assertThatThrownBy(() -> connector().discoverTools(target(McpTransport.STREAMABLE_HTTP), Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(McpHttpConnectorException.class, error -> {
                    assertThat(error.category()).isEqualTo(McpHttpFailureCategory.RATE_LIMITED);
                    assertThat(error.statusCode()).isEqualTo(429);
                    assertThat(error.getMessage()).doesNotContain("secret");
                });
    }

    @ParameterizedTest
    @CsvSource({"401,UNAUTHORIZED", "403,FORBIDDEN", "500,UPSTREAM_5XX", "302,REDIRECT_NOT_ALLOWED"})
    void commonHttpStatusesHaveStableCategories(int status, McpHttpFailureCategory category) throws Exception {
        server = server(exchange -> respond(exchange, status, "text/plain", "upstream detail"));

        assertThatThrownBy(() -> connector().discoverTools(target(McpTransport.STREAMABLE_HTTP), Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(McpHttpConnectorException.class,
                        error -> assertThat(error.category()).isEqualTo(category));
    }

    @Test
    void networkErrorsAreClassified() throws Exception {
        server = server(exchange -> respond(exchange, 200, "application/json", "{}"));
        int port = server.getAddress().getPort();
        server.stop(0);

        assertThatThrownBy(() -> connector().discoverTools(new McpConnectorTarget(UUID.randomUUID(), "test",
                McpTransport.STREAMABLE_HTTP, URI.create("http://localhost:" + port + "/mcp")), Duration.ofMillis(200)))
                .isInstanceOfSatisfying(McpHttpConnectorException.class,
                        error -> assertThat(error.category()).isEqualTo(McpHttpFailureCategory.CONNECTION_FAILURE));
    }

    @Test
    void invalidToolsListSchemaIsRejected() throws Exception {
        server = server(exchange -> respond(exchange, 200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{\"name\":\"bad\"}]}}"));

        assertThatThrownBy(() -> connector().discoverTools(target(McpTransport.STREAMABLE_HTTP), Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(McpHttpConnectorException.class,
                        error -> assertThat(error.category()).isEqualTo(McpHttpFailureCategory.PROTOCOL_ERROR));
    }

    @Test
    void redirectFollowingClientIsRejectedAtConstruction() {
        McpHttpConnectorProperties properties = properties();
        HttpClient redirecting = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

        assertThatThrownBy(() -> new McpHttpTransportConnector(redirecting, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disable redirects");
    }

    @Test
    void supportsBothHttpTransports() {
        McpHttpTransportConnector connector = connector();

        assertThat(connector.supports(McpTransport.STREAMABLE_HTTP)).isTrue();
        assertThat(connector.supports(McpTransport.SSE)).isTrue();
    }

    private McpHttpTransportConnector connector() {
        return new McpHttpTransportConnector(client(), properties());
    }

    private HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private McpHttpConnectorProperties properties() {
        McpHttpConnectorProperties properties = new McpHttpConnectorProperties();
        properties.setAllowedSchemes(List.of("http"));
        properties.setAllowedHosts(List.of("localhost"));
        properties.setMaxTimeout(Duration.ofSeconds(2));
        return properties;
    }

    private McpConnectorTarget target(McpTransport transport) {
        return new McpConnectorTarget(UUID.randomUUID(), "test", transport,
                URI.create("http://localhost:" + server.getAddress().getPort() + "/mcp"));
    }

    private HttpServer server(Handler handler) throws IOException {
        HttpServer value = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        value.createContext("/mcp", exchange -> handler.handle(exchange));
        value.start();
        return value;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
