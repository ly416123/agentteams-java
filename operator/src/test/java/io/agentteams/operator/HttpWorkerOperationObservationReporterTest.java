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
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpWorkerOperationObservationReporterTest {
    private HttpServer server;
    private AtomicReference<String> method;
    private AtomicReference<String> path;
    private AtomicReference<String> requestBody;
    private CountDownLatch postReceived;

    @BeforeEach
    void setUp() throws IOException {
        method = new AtomicReference<>();
        path = new AtomicReference<>();
        requestBody = new AtomicReference<>();
        postReceived = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void discoversActiveRolloutAndReportsCurrentOperatorFacts() throws InterruptedException {
        UUIDs ids = UUIDs.create();
        Worker worker = worker(ids.agentId());
        WorkerStatus status = readyStatus();
        HttpWorkerOperationObservationReporter reporter = reporter();

        reporter.report(worker, status, Instant.parse("2030-01-01T00:01:00Z"));
        assertThat(postReceived.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get()).isEqualTo("/internal/v1/worker-operations/" + ids.operationId() + "/operator");
        assertThat(requestBody.get()).contains("\"expectedVersion\":4")
                .contains("\"ready\":true")
                .contains("\"specDigest\":\"sha256:worker\"")
                .contains("\"configRevision\":\"config-7\"")
                .contains("\"secretGeneration\":\"secret-3\"");
    }

    @Test
    void ignoresMissingOperationWithoutTurningReconcileIntoFailure() {
        Worker worker = worker("11111111-1111-1111-1111-111111111111");
        server.removeContext("/");
        server.createContext("/internal/v1/worker-operations/active/11111111-1111-1111-1111-111111111111",
                exchange -> respond(exchange, 404, ""));

        reporter().report(worker, readyStatus(), Instant.parse("2030-01-01T00:01:00Z"));

        assertThat(method.get()).isNull();
    }

    private HttpWorkerOperationObservationReporter reporter() {
        return new HttpWorkerOperationObservationReporter(HttpClient.newHttpClient(), new ObjectMapper(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "internal-secret");
    }

    private void handle(HttpExchange exchange) throws IOException {
        method.set(exchange.getRequestMethod());
        path.set(exchange.getRequestURI().getPath());
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if ("GET".equals(method.get())) {
            UUIDs ids = UUIDs.create();
            respond(exchange, 200, """
                    {"id":"%s","agentId":"%s","type":"ROLLOUT","status":"RUNNING",
                     "requestedSpecDigest":"sha256:worker","requestedRuntime":"qwenpaw",
                     "requestedConfigRevision":"config-7","requestedSecretGeneration":"secret-3",
                     "version":4}
                    """.formatted(ids.operationId(), ids.agentId()));
            return;
        }
        postReceived.countDown();
        respond(exchange, 202, "{}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static Worker worker(String agentId) {
        Worker worker = new Worker();
        worker.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
                .withName("worker-a").withNamespace("agentteams").build());
        worker.setSpec(new WorkerSpec(agentId, "qwenpaw", "example/worker:v1", 1, Map.of(), "",
                "sha256:worker", "config-7", "secret-3"));
        return worker;
    }

    private static WorkerStatus readyStatus() {
        WorkerStatus status = new WorkerStatus();
        status.setPhase("Ready");
        status.setObservedSpecDigest("sha256:worker");
        status.setObservedRuntime("qwenpaw");
        status.setObservedConfigRevision("config-7");
        status.setObservedSecretGeneration("secret-3");
        return status;
    }

    private record UUIDs(String agentId, String operationId) {
        static UUIDs create() {
            return new UUIDs("11111111-1111-1111-1111-111111111111",
                    "22222222-2222-2222-2222-222222222222");
        }
    }
}
