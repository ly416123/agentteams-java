package io.agentteams.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QwenPawHttpRuntimePortTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private AtomicReference<String> requestBody;
    private AtomicReference<String> agentId;
    private AtomicReference<String> authorization;
    private AtomicInteger requestCount;

    @BeforeEach
    void setUp() throws IOException {
        requestBody = new AtomicReference<>();
        agentId = new AtomicReference<>();
        authorization = new AtomicReference<>();
        requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsOfficialRequestAndAggregatesCompletedSseEvent() throws Exception {
        server.createContext("/api/console/chat", exchange -> {
            captureRequest(exchange);
            writeResponse(exchange, 200, "text/event-stream",
                    "data: {\"status\":\"created\"}\n\n"
                            + "data: {\"status\":\"in_progress\",\"output\":[{\"content\":[{\"text\":\"hel\"}]}]}\n\n"
                            + "data: {\"status\":\"completed\",\"output\":[{\"content\":[{\"text\":\"hello\"}]}]}\n\n");
        });
        server.start();

        QwenPawHttpRuntimePort port = port();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<RuntimeResult> result = new AtomicReference<>();
        RuntimeTask task = task();
        port.start(context(), value -> {
            result.set(value);
            completed.countDown();
        });
        port.submit(task);

        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get()).isEqualTo(RuntimeResult.success(task.id(), "hello", Instant.EPOCH));

        JsonNode request = MAPPER.readTree(requestBody.get());
        assertThat(agentId.get()).isEqualTo("default");
        assertThat(authorization.get()).isEqualTo("Bearer secret");
        assertThat(request.path("input").get(0).path("role").asText()).isEqualTo("user");
        assertThat(request.path("input").get(0).path("content").get(0).path("text").asText())
                .isEqualTo(task.inputJson());
        assertThat(request.path("session_id").asText()).isEqualTo(task.id().toString());
        assertThat(request.path("user_id").asText()).isEqualTo("agentteams");
        assertThat(request.path("channel").asText()).isEqualTo("console");
        port.stop();
    }

    @Test
    void appliesConfigurationThroughRuntimeEndpoint() throws Exception {
        server.createContext("/api/models/active", exchange -> {
            captureRequest(exchange);
            assertThat(exchange.getRequestMethod()).isEqualTo("PUT");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        QwenPawHttpRuntimePort port = port();
        port.start(context(), value -> { });
        port.applyConfig(new RuntimeConfigSnapshot(4, "sha-4",
                Map.of("provider_id", "deepseek", "model", "deepseek")));

        JsonNode request = MAPPER.readTree(requestBody.get());
        assertThat(agentId.get()).isEqualTo("default");
        assertThat(authorization.get()).isEqualTo("Bearer secret");
        assertThat(request.path("provider_id").asText()).isEqualTo("deepseek");
        assertThat(request.path("model").asText()).isEqualTo("deepseek");
        assertThat(request.path("scope").asText()).isEqualTo("agent");
        assertThat(request.path("agent_id").asText()).isEqualTo("default");
        port.stop();
    }

    @Test
    void extractsFinalAssistantMessageFromQwenPawResponseSnapshot() throws Exception {
        server.createContext("/api/console/chat", exchange -> writeResponse(exchange, 200,
                "text/event-stream",
                "data: {\"status\":\"created\",\"output\":[],\"object\":\"response\"}\n\n"
                        + "data: {\"type\":\"message\",\"role\":\"assistant\",\"content\":[],"
                        + "\"status\":\"in_progress\"}\n\n"
                        + "data: {\"type\":\"text\",\"delta\":false,\"text\":\"QWENPAW_OUTPUT_OK\"}\n\n"
                        + "data: {\"id\":\"reasoning-1\",\"type\":\"reasoning\",\"role\":\"assistant\","
                        + "\"content\":[{\"text\":\"internal\"}],\"status\":\"completed\","
                        + "\"object\":\"message\"}\n\n"
                        + "data: {\"id\":\"response-1\",\"status\":\"completed\","
                        + "\"output\":["
                        + "{\"type\":\"reasoning\",\"role\":\"assistant\",\"content\":[{\"text\":\"internal\"}]},"
                        + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"text\":\"QWENPAW_OUTPUT_OK\"}]}"
                        + "]}\n\n"));
        server.start();

        QwenPawHttpRuntimePort port = port();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<RuntimeResult> result = new AtomicReference<>();
        RuntimeTask task = task();
        port.start(context(), value -> {
            result.set(value);
            completed.countDown();
        });
        port.submit(task);

        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get()).isEqualTo(RuntimeResult.success(task.id(), "QWENPAW_OUTPUT_OK", Instant.EPOCH));
        port.stop();
    }

    @Test
    void convertsFailedSseAndHttpErrorsToRuntimeFailures() throws Exception {
        server.createContext("/api/console/chat", exchange -> {
            captureRequest(exchange);
            if (requestCount.get() == 1) {
                writeResponse(exchange, 200, "text/event-stream",
                        "data: {\"status\":\"failed\",\"error\":{\"message\":\"model unavailable\"}}\n\n");
            } else {
                writeResponse(exchange, 503, "application/json", "{\"message\":\"unavailable\"}");
            }
        });
        server.start();

        QwenPawHttpRuntimePort port = port();
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        AtomicReference<RuntimeResult> firstResult = new AtomicReference<>();
        AtomicReference<RuntimeResult> secondResult = new AtomicReference<>();
        AtomicInteger resultCount = new AtomicInteger();
        port.start(context(), value -> {
            if (resultCount.getAndIncrement() == 0) {
                firstResult.set(value);
                first.countDown();
            } else {
                secondResult.set(value);
                second.countDown();
            }
        });
        port.submit(task());

        assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(firstResult.get().success()).isFalse();
        assertThat(firstResult.get().output()).contains("model unavailable");
        port.submit(task());

        assertThat(second.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(secondResult.get().success()).isFalse();
        assertThat(secondResult.get().output()).contains("503");
        port.stop();
    }

    @Test
    void cancellationSuppressesLateSseCompletion() throws Exception {
        CountDownLatch responseReady = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        server.createContext("/api/console/chat", exchange -> {
            captureRequest(exchange);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            responseReady.countDown();
            try {
                try {
                    releaseResponse.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                exchange.getResponseBody().write(
                        "data: {\"status\":\"completed\",\"output\":[]}".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } finally {
                exchange.close();
            }
        });
        server.start();

        QwenPawHttpRuntimePort port = port();
        CountDownLatch completed = new CountDownLatch(1);
        RuntimeTask task = task();
        port.start(context(), value -> completed.countDown());
        port.submit(task);
        assertThat(responseReady.await(5, TimeUnit.SECONDS)).isTrue();

        port.cancel(task.id());
        releaseResponse.countDown();
        Thread.sleep(200);

        assertThat(completed.getCount()).isEqualTo(1);
        port.stop();
    }

    @Test
    void validatesLifecycleAndConfiguration() {
        assertThatThrownBy(() -> new QwenPawHttpRuntimeConfiguration(
                URI.create("http://localhost:8088"), "", null, Duration.ofSeconds(1), "agentteams", "console"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentId");

        QwenPawHttpRuntimePort port = port();
        assertThatThrownBy(() -> port.submit(task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("started");
    }

    private QwenPawHttpRuntimePort port() {
        return new QwenPawHttpRuntimePort(
                new QwenPawHttpRuntimeConfiguration(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        "default", "secret", Duration.ofSeconds(2), "agentteams", "console"),
                HttpClient.newHttpClient(), MAPPER);
    }

    private static AgentRuntimeContext context() {
        return new AgentRuntimeContext("qwenpaw", 2,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), value -> { }, Map.of());
    }

    private static RuntimeTask task() {
        return new RuntimeTask(UUID.randomUUID(), "chat", "hello", Map.of());
    }

    private void captureRequest(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        agentId.set(exchange.getRequestHeaders().getFirst("X-Agent-Id"));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    }

    private static void writeResponse(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
