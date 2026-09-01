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
    void sendsSignatureAndIdempotencyHeadersForProjectCreation() {
        server.createContext("/api/v1/projects", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("AT-HMAC-SHA256 Credential=ak-1");
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).startsWith("sdk-");
            respond(exchange, 201, "{\"id\":\"00000000-0000-0000-0000-000000000010\",\"tenantId\":\"tenant-1\",\"name\":\"Demo\",\"status\":\"ACTIVE\",\"createdBy\":\"user-1\"}");
        });

        AgentTeamsClient client = new AgentTeamsClient(baseUrl, "ak-1", "secret-1", "org-1").asUser("user-1");
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
        AgentTeamsClient client = new AgentTeamsClient(baseUrl, "ak-1", "secret-1", "org-1", 2, 0).asUser("user-1");

        assertThat(client.getTask(UUID.fromString("00000000-0000-0000-0000-000000000001")).id())
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(getAttempts).hasValue(2);
    }

    @Test
    void mapsStructuredConflictWithoutReturningRawResponseBody() {
        server.createContext("/api/v1/tasks/", exchange ->
                respond(exchange, 409, "{\"code\":\"VERSION_CONFLICT\",\"message\":\"资源版本冲突\",\"correlationId\":\"corr-1\",\"details\":{\"expectedVersion\":3}}"));
        AgentTeamsClient client = new AgentTeamsClient(baseUrl, "ak-1", "secret-1", "org-1", 0, 0).asUser("user-1");

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

    @Test
    void readsTaskProgressResultAndProcessEvents() {
        server.createContext("/api/v1/tasks/00000000-0000-0000-0000-000000000001/runs/00000000-0000-0000-0000-000000000002/progress", exchange ->
                respond(exchange, 200, "{\"phase\":\"EXECUTION\",\"completed\":2,\"total\":4,\"progress\":50,\"waitingReason\":\"\"}"));
        server.createContext("/api/v1/tasks/00000000-0000-0000-0000-000000000001/runs/00000000-0000-0000-0000-000000000002/result", exchange ->
                respond(exchange, 200, "{\"taskId\":\"00000000-0000-0000-0000-000000000001\",\"runId\":\"00000000-0000-0000-0000-000000000002\",\"status\":\"SUCCEEDED\",\"summary\":\"done\",\"artifacts\":[]}"));
        server.createContext("/api/v1/tasks/00000000-0000-0000-0000-000000000001/runs/00000000-0000-0000-0000-000000000002/process-events", exchange ->
                respond(exchange, 200, "[{\"eventId\":\"00000000-0000-0000-0000-000000000003\",\"taskId\":\"00000000-0000-0000-0000-000000000001\",\"runId\":\"00000000-0000-0000-0000-000000000002\",\"sequence\":1,\"eventType\":\"PROGRESS\",\"visibility\":\"REQUESTER\",\"occurredAt\":\"2026-08-31T00:00:00Z\",\"correlationId\":\"corr-1\",\"payload\":\"{\\\"progress\\\":50}\"}]"));

        AgentTeamsClient client = new AgentTeamsClient(baseUrl, "ak-1", "secret-1", "org-1").asUser("user-1");

        assertThat(client.getTaskProgress(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002")).progress()).isEqualTo(50);
        assertThat(client.getTaskResult(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002")).status()).isEqualTo("SUCCEEDED");
        assertThat(client.listTaskProcessEvents(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"), 0)).hasSize(1);
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
