package io.agentteams.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTeamsClientTest {
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsBearerAndIdempotencyHeadersForProjectCreation() {
        server.createContext("/api/v1/projects", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer token-1");
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).startsWith("sdk-");
            respond(exchange, 201, "{\"id\":\"00000000-0000-0000-0000-000000000010\",\"tenantId\":\"tenant-1\",\"name\":\"Demo\",\"status\":\"ACTIVE\",\"createdBy\":\"user-1\"}");
        });

        AgentTeamsClient client = new AgentTeamsClient(baseUrl, () -> "token-1");
        AgentTeamsClient.Project project = client.createProject(new AgentTeamsClient.CreateProjectRequest("Demo"));

        assertThat(project.id()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000010"));
        assertThat(project.name()).isEqualTo("Demo");
    }

    @Test
    void retriesGetButOnlyRetriesWriteWhenExplicitlySafe() {
        AtomicInteger getAttempts = new AtomicInteger();
        server.createContext("/api/v1/tasks/", exchange -> {
            if (getAttempts.incrementAndGet() == 1) {
                respond(exchange, 503, "{\"code\":\"TEMPORARY\",\"message\":\"retry\"}");
            } else {
                respond(exchange, 200, taskJson());
            }
        });
        AgentTeamsClient client = new AgentTeamsClient(baseUrl, () -> "token-1", 2, 0);

        assertThat(client.getTask(UUID.fromString("00000000-0000-0000-0000-000000000001")).id())
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(getAttempts).hasValue(2);
    }

    @Test
    void mapsStructuredConflictWithoutReturningRawResponseBody() {
        server.createContext("/api/v1/tasks/", exchange ->
                respond(exchange, 409, "{\"code\":\"VERSION_CONFLICT\",\"message\":\"资源版本冲突\",\"correlationId\":\"corr-1\",\"details\":{\"expectedVersion\":3}}"));
        AgentTeamsClient client = new AgentTeamsClient(baseUrl, () -> "token-1", 0, 0);

        assertThatThrownBy(() -> client.cancelTask(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new AgentTeamsClient.LifecycleRequest(3L, null, null)))
                .isInstanceOf(AgentTeamsClient.ApiErrorException.class)
                .hasMessage("资源版本冲突")
                .satisfies(error -> {
                    AgentTeamsClient.ApiErrorException apiError = (AgentTeamsClient.ApiErrorException) error;
                    assertThat(apiError.status()).isEqualTo(409);
                    assertThat(apiError.code()).isEqualTo("VERSION_CONFLICT");
                    assertThat(apiError.correlationId()).isEqualTo("corr-1");
                });
    }

    private static String taskJson() {
        return "{\"id\":\"00000000-0000-0000-0000-000000000001\",\"title\":\"Demo\","
                + "\"description\":\"Task\",\"phase\":\"DRAFT\",\"priority\":0,"
                + "\"createdAt\":\"2026-08-31T00:00:00Z\",\"updatedAt\":\"2026-08-31T00:00:00Z\",\"version\":0}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
