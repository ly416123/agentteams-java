package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentteams.application.api.QuotaReservationPort;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class ControlPlaneQuotaReservationClientTest {
    private HttpServer server;
    private String requestBody;
    private String token;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/quota/acquire", this::acquire);
        server.createContext("/internal/v1/quota/release", this::release);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void forwardsAcquireAndReleaseToDurableControlPlaneBoundary() {
        GatewayQuotaProperties properties = new GatewayQuotaProperties();
        properties.setControlPlaneUrl(java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        properties.setInternalToken("test-token");
        properties.setRequestTimeout(Duration.ofSeconds(2));
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ControlPlaneQuotaReservationClient client = new ControlPlaneQuotaReservationClient(
                HttpClient.newHttpClient(), mapper, properties);

        QuotaReservationPort.AcquireDecision acquired = client.acquire(new QuotaReservationPort.AcquireRequest(
                "tenant-a", "project-a", "acquire-1", 10, 1, Instant.parse("2030-01-01T00:00:00Z"),
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01", ""));
        assertThat(acquired.accepted()).isTrue();
        assertThat(acquired.reservationId()).isEqualTo("reservation-1");
        assertThat(requestBody).contains("\"projectId\":\"project-a\"");
        assertThat(token).isEqualTo("test-token");

        QuotaReservationPort.ReleaseDecision released = client.release(new QuotaReservationPort.ReleaseRequest(
                "tenant-a", "project-a", "reservation-1", "release-1",
                Instant.parse("2030-01-01T00:00:00Z"), "", ""));
        assertThat(released.accepted()).isTrue();
        assertThat(released.reservationId()).isEqualTo("reservation-1");
    }

    private void acquire(HttpExchange exchange) throws IOException {
        requestBody = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        token = exchange.getRequestHeaders().getFirst("X-AgentTeams-Internal-Token");
        respond(exchange, "{\"accepted\":true,\"reservationId\":\"reservation-1\","
                + "\"rejectionDimension\":\"\",\"retryAfterMillis\":0,\"protocolError\":\"\"}");
    }

    private void release(HttpExchange exchange) throws IOException {
        requestBody = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        token = exchange.getRequestHeaders().getFirst("X-AgentTeams-Internal-Token");
        respond(exchange, "{\"accepted\":true,\"reservationId\":\"reservation-1\",\"protocolError\":\"\"}");
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
