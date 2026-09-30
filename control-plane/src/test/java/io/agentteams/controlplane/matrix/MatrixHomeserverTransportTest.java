package io.agentteams.controlplane.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MatrixHomeserverTransportTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsRoomMessageWithStableOutboxTransactionIdAndBearerToken() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> payload = new AtomicReference<>();
        ObjectMapper mapper = new ObjectMapper();
        startServer(exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getRawPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            payload.set(mapper.readTree(exchange.getRequestBody()));
            exchange.sendResponseHeaders(200, -1);
        });
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000011");

        transport(mapper).send(message(id, "!room:example.org", "safe summary"));

        assertThat(method.get()).isEqualTo("PUT");
        assertThat(path.get()).isEqualTo("/_matrix/client/v3/rooms/%21room%3Aexample.org/send/m.room.message/" + id);
        assertThat(authorization.get()).isEqualTo("Bearer test-token");
        assertThat(payload.get().path("msgtype").asText()).isEqualTo("m.text");
        assertThat(payload.get().path("body").asText()).isEqualTo("safe summary");
    }

    @Test
    void classifiesRateLimitAsRetryableWithoutLeakingResponseBody() throws Exception {
        startServer(exchange -> {
            byte[] body = "secret response".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Retry-After", "3");
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        assertThatThrownBy(() -> transport(new ObjectMapper()).send(
                message(UUID.randomUUID(), "!room:example.org", "summary")))
                .isInstanceOf(MatrixDeliveryException.class)
                .satisfies(error -> {
                    MatrixDeliveryException delivery = (MatrixDeliveryException) error;
                    assertThat(delivery.retryable()).isTrue();
                    assertThat(delivery.retryAfter()).contains(Duration.ofSeconds(3));
                    assertThat(delivery.getMessage()).doesNotContain("secret response", "test-token");
                });
    }

    @Test
    void classifiesAuthenticationFailureAsPermanent() throws Exception {
        startServer(exchange -> exchange.sendResponseHeaders(401, -1));

        assertThatThrownBy(() -> transport(new ObjectMapper()).send(
                message(UUID.randomUUID(), "!room:example.org", "summary")))
                .isInstanceOf(MatrixDeliveryException.class)
                .satisfies(error -> assertThat(((MatrixDeliveryException) error).retryable()).isFalse());
    }

    private MatrixHomeserverTransport transport(ObjectMapper mapper) {
        return new MatrixHomeserverTransport(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test-token",
                Duration.ofSeconds(2), 1024, mapper);
    }

    private void startServer(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
    }

    private static MatrixOutboundMessage message(UUID id, String roomId, String body) {
        return new MatrixOutboundMessage(id, roomId, "task.completed", body,
                "IN_FLIGHT", 1, Instant.parse("2026-09-30T00:00:00Z"), null);
    }
}
