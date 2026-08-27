package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpGatewayWorkerOperationObservationReporterTest {
    private static final String AGENT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OPERATION_ID = "22222222-2222-2222-2222-222222222222";

    private HttpServer server;
    private AtomicReference<String> method;
    private AtomicReference<String> path;
    private AtomicReference<String> requestBody;

    @BeforeEach
    void setUp() throws IOException {
        method = new AtomicReference<>();
        path = new AtomicReference<>();
        requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void discoversActiveRolloutAndReportsCurrentGatewayFacts() {
        ConnectionRegistry.ConnectionSnapshot connection = connection();

        reporter().report(connection, true, Instant.parse("2030-01-01T00:01:00Z"));

        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get()).isEqualTo("/internal/v1/worker-operations/" + OPERATION_ID + "/gateway");
        assertThat(requestBody.get()).contains("\"expectedVersion\":4")
                .contains("\"online\":true")
                .contains("\"specDigest\":\"sha256:worker\"")
                .contains("\"runtime\":\"qwenpaw\"")
                .contains("\"configRevision\":\"config-7\"")
                .contains("\"secretGeneration\":\"secret-3\"");
    }

    @Test
    void ignoresMissingOperationAndInvalidAgentWithoutThrowing() {
        server.removeContext("/");
        server.createContext("/internal/v1/worker-operations/active/" + AGENT_ID,
                exchange -> respond(exchange, 404, ""));

        reporter().report(connection(), false, Instant.parse("2030-01-01T00:01:00Z"));
        reporter().report(new ConnectionRegistry.ConnectionSnapshot(UUID.randomUUID(), "legacy-agent", "qwenpaw",
                "0.4.0", Map.of(), Instant.parse("2030-01-01T00:00:00Z"), 0), true,
                Instant.parse("2030-01-01T00:01:00Z"));

        assertThat(method.get()).isNull();
    }

    private HttpGatewayWorkerOperationObservationReporter reporter() {
        GatewayOperationProperties properties = new GatewayOperationProperties();
        properties.setControlPlaneUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        properties.setInternalToken("internal-secret");
        return new HttpGatewayWorkerOperationObservationReporter(HttpClient.newHttpClient(),
                new ObjectMapper(), properties);
    }

    private void handle(HttpExchange exchange) throws IOException {
        method.set(exchange.getRequestMethod());
        path.set(exchange.getRequestURI().getPath());
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if ("GET".equals(method.get())) {
            respond(exchange, 200, """
                    {"id":"%s","agentId":"%s","type":"ROLLOUT","status":"RUNNING","version":4}
                    """.formatted(OPERATION_ID, AGENT_ID));
        } else {
            respond(exchange, 202, "{}");
        }
    }

    private static ConnectionRegistry.ConnectionSnapshot connection() {
        return new ConnectionRegistry.ConnectionSnapshot(UUID.randomUUID(), AGENT_ID, "qwenpaw", "0.4.0",
                "sha256:worker", "config-7", "secret-3", Map.of(), Instant.parse("2030-01-01T00:00:00Z"), 0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
