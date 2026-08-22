package io.agentteams.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.agentteams.contracts.v1.EventMetadata;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkerTracingTest {

    @AfterEach
    void resetGlobalProvider() {
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void keepsTracingNoopByDefault() {
        WorkerTracing.Bridge bridge = WorkerTracing.create(Map.of());

        try {
            assertThat(bridge.enabled()).isFalse();
        } finally {
            bridge.close();
        }
    }

    @Test
    void readsStandardEndpointAndServiceNameFallbacks() {
        WorkerTracing.Configuration configuration = WorkerTracing.Configuration.from(Map.of(
                "AGENTTEAMS_OBSERVABILITY_TRACING_ENABLED", "true",
                "AGENTTEAMS_OBSERVABILITY_TRACING_SAMPLING_PROBABILITY", "0.25",
                "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", " http://otel:4318/v1/traces ",
                "OTEL_SERVICE_NAME", "worker-from-otel"));

        assertThat(configuration.enabled()).isTrue();
        assertThat(configuration.samplingProbability()).isEqualTo(0.25);
        assertThat(configuration.otlpEndpoint()).isEqualTo("http://otel:4318/v1/traces");
        assertThat(configuration.serviceName()).isEqualTo("worker-from-otel");
    }

    @Test
    void rejectsSamplingProbabilityOutsideClosedUnitInterval() {
        assertThatThrownBy(() -> WorkerTracing.Configuration.from(Map.of(
                "AGENTTEAMS_OBSERVABILITY_TRACING_SAMPLING_PROBABILITY", "1.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be between 0 and 1");
    }

    @Test
    void registersSdkWhenTracingIsEnabledWithEndpoint() {
        WorkerTracing.Bridge bridge = WorkerTracing.create(Map.of(
                "AGENTTEAMS_OBSERVABILITY_TRACING_ENABLED", "true",
                "AGENTTEAMS_OBSERVABILITY_OTLP_TRACING_ENDPOINT", "http://127.0.0.1:4318/v1/traces"));

        try {
            assertThat(bridge.enabled()).isTrue();
            assertThat(GlobalOpenTelemetry.getTracerProvider().get("agentteams-agent-worker"))
                    .isNotNull();
        } finally {
            bridge.close();
        }
    }

    @Test
    void exportsSampledWorkerSpanAsOtlpHttp() throws IOException {
        HttpServer collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<byte[]> requestBody = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        collector.createContext("/v1/traces", exchange -> {
            requestBody.set(exchange.getRequestBody().readAllBytes());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        collector.start();

        WorkerTracing.Bridge bridge = WorkerTracing.create(Map.of(
                "AGENTTEAMS_OBSERVABILITY_TRACING_ENABLED", "true",
                "AGENTTEAMS_OBSERVABILITY_TRACING_SAMPLING_PROBABILITY", "1.0",
                "AGENTTEAMS_OBSERVABILITY_OTLP_TRACING_ENDPOINT",
                "http://127.0.0.1:" + collector.getAddress().getPort() + "/v1/traces"));
        try {
            try (WorkerTracing.Scope ignored = bridge.start("agentteams.worker.test",
                    EventMetadata.getDefaultInstance())) {
                // The scope close is enough to enqueue a sampled span.
            }
        } finally {
            bridge.close();
            collector.stop(0);
        }

        assertThat(requestBody.get()).isNotNull().isNotEmpty();
        assertThat(contentType.get()).contains("application/x-protobuf");
    }

    @Test
    void keepsSpanCompletionLocalWhenCollectorReturnsError() throws IOException {
        HttpServer collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        collector.createContext("/v1/traces", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        collector.start();

        WorkerTracing.Bridge bridge = WorkerTracing.create(Map.of(
                "AGENTTEAMS_OBSERVABILITY_TRACING_ENABLED", "true",
                "AGENTTEAMS_OBSERVABILITY_TRACING_SAMPLING_PROBABILITY", "1.0",
                "AGENTTEAMS_OBSERVABILITY_OTLP_TRACING_ENDPOINT",
                "http://127.0.0.1:" + collector.getAddress().getPort() + "/v1/traces"));
        long started = System.nanoTime();
        try {
            try (WorkerTracing.Scope ignored = bridge.start("agentteams.worker.failure-test",
                    EventMetadata.getDefaultInstance())) {
                // Export is handled by the batch processor, not by the task thread.
            }
        } finally {
            Duration completion = Duration.ofNanos(System.nanoTime() - started);
            bridge.close();
            collector.stop(0);
            assertThat(completion).isLessThan(Duration.ofSeconds(1));
        }
    }
}
