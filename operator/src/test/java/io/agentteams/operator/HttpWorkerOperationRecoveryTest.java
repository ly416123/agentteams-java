package io.agentteams.operator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpWorkerOperationRecoveryTest {
    private static final String AGENT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OPERATION_ID = "22222222-2222-2222-2222-222222222222";
    private HttpServer server;
    private AtomicReference<String> path;
    private AtomicReference<String> requestBody;

    @BeforeEach
    void setUp() throws IOException {
        path = new AtomicReference<>();
        requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    @Test
    void discoversFailedRolloutAndConfirmsRollback() {
        HttpWorkerOperationRecovery recovery = new HttpWorkerOperationRecovery(HttpClient.newHttpClient(),
                new ObjectMapper(), URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "internal-secret", java.time.Duration.ofSeconds(2));

        WorkerOperationRecovery.FailedWorkerOperation failed = recovery.failed(UUID.fromString(AGENT_ID)).orElseThrow();
        recovery.rollback(UUID.fromString(OPERATION_ID), 7);

        assertThat(failed.id()).isEqualTo(UUID.fromString(OPERATION_ID));
        assertThat(failed.previousStableSpec()).contains("agentId");
        assertThat(path.get()).isEqualTo("/internal/v1/worker-operations/" + OPERATION_ID + "/rollback");
        assertThat(requestBody.get()).contains("\"expectedVersion\":7");
    }

    private void handle(HttpExchange exchange) throws IOException {
        path.set(exchange.getRequestURI().getPath());
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (exchange.getRequestMethod().equals("GET")) {
            respond(exchange, 200, """
                    {"id":"%s","agentId":"%s","previousStableSpec":"{\\"agentId\\":\\"%s\\"}","version":7}
                    """.formatted(OPERATION_ID, AGENT_ID, AGENT_ID));
        } else {
            respond(exchange, 200, "{}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
