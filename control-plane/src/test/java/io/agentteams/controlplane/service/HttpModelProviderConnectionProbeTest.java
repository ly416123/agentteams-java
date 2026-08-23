package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentteams.controlplane.security.SecretResolver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpModelProviderConnectionProbeTest {

    private HttpServer server;
    private ExecutorService serverExecutor;
    private HttpModelProviderConnectionProbe probe;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
        ModelProviderConnectionProbeProperties properties = new ModelProviderConnectionProbeProperties();
        properties.setAllowedSchemes(List.of("http"));
        properties.setAllowedHosts(List.of("localhost"));
        properties.setMaxTimeout(Duration.ofSeconds(2));
        probe = new HttpModelProviderConnectionProbe(
                reference -> new SecretResolver.Resolution(SecretResolver.Status.VALIDATION_ONLY),
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), properties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @Test
    void classifiesSuccessfulResponseAsReachableButUnauthenticated() {
        server.createContext("/ok", exchange -> respond(exchange, 204));

        ModelProviderConnectionProbe.ProbeResult result = probe.probe(request("/ok", Duration.ofSeconds(1)));

        assertThat(result.status()).isEqualTo(ModelProviderConnectionProbe.ProbeResult.Status.CONNECTED);
        assertThat(result.classification()).isEqualTo("ENDPOINT_REACHABLE_UNAUTHENTICATED_2XX");
        assertThat(result.checks()).extracting(ModelProviderConnectionProbe.ProbeResult.Check::status)
                .contains("RESOLVED_BUT_NOT_SENT");
    }

    @Test
    void classifiesAuthAndRateLimitResponsesWithoutSendingCredential() {
        server.createContext("/auth", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            respond(exchange, 401);
        });
        server.createContext("/limit", exchange -> respond(exchange, 429));

        assertThat(probe.probe(request("/auth", Duration.ofSeconds(1))).classification())
                .isEqualTo("ENDPOINT_REACHABLE_UNAUTHENTICATED_401");
        assertThat(probe.probe(request("/limit", Duration.ofSeconds(1))).classification())
                .isEqualTo("ENDPOINT_REACHABLE_RATE_LIMITED_429");
    }

    @Test
    void classifiesForbiddenAndUpstreamFailures() {
        server.createContext("/forbidden", exchange -> respond(exchange, 403));
        server.createContext("/upstream", exchange -> respond(exchange, 503));

        assertThat(probe.probe(request("/forbidden", Duration.ofSeconds(1))).classification())
                .isEqualTo("ENDPOINT_REACHABLE_UNAUTHENTICATED_403");
        assertThat(probe.probe(request("/upstream", Duration.ofSeconds(1))).classification())
                .isEqualTo("ENDPOINT_REACHABLE_UPSTREAM_5XX");
    }

    @Test
    void rejectsNonAllowlistedEndpointBeforeNetworkCall() {
        ModelProviderConnectionProbe.ProbeResult result = probe.probe(
                new ModelProviderConnectionProbe.ProbeRequest(UUID.randomUUID(), "openai-compatible",
                        "https://example.com/v1", "secret/provider", Duration.ofSeconds(1)));

        assertThat(result.status()).isEqualTo(ModelProviderConnectionProbe.ProbeResult.Status.REJECTED);
        assertThat(result.networkCallAttempted()).isFalse();
        assertThat(result.classification()).isEqualTo("ENDPOINT_NOT_ALLOWED");
    }

    @Test
    void neverFollowsRedirect() {
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/ok");
            respond(exchange, 302);
        });

        ModelProviderConnectionProbe.ProbeResult result = probe.probe(request("/redirect", Duration.ofSeconds(1)));

        assertThat(result.status()).isEqualTo(ModelProviderConnectionProbe.ProbeResult.Status.FAILED);
        assertThat(result.classification()).isEqualTo("REDIRECT_NOT_ALLOWED");
    }

    @Test
    void rejectsTimeoutAboveConfiguredMaximum() {
        ModelProviderConnectionProbe.ProbeResult result = probe.probe(request("/ok", Duration.ofSeconds(3)));

        assertThat(result.status()).isEqualTo(ModelProviderConnectionProbe.ProbeResult.Status.REJECTED);
        assertThat(result.networkCallAttempted()).isFalse();
        assertThat(result.classification()).isEqualTo("TIMEOUT_INVALID");
    }

    @Test
    void classifiesRequestTimeout() {
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 204);
        });

        ModelProviderConnectionProbe.ProbeResult result = probe.probe(request("/slow", Duration.ofMillis(25)));

        assertThat(result.status()).isEqualTo(ModelProviderConnectionProbe.ProbeResult.Status.FAILED);
        assertThat(result.classification()).isEqualTo("TIMEOUT");
    }

    @Test
    void classifiesConnectionFailureWithoutExposingEndpointDetails() {
        int port = server.getAddress().getPort();
        server.stop(0);

        ModelProviderConnectionProbe.ProbeResult result = probe.probe(requestAtPort("/down", Duration.ofMillis(100), port));

        assertThat(result.status()).isEqualTo(ModelProviderConnectionProbe.ProbeResult.Status.FAILED);
        assertThat(result.classification()).isEqualTo("NETWORK_ERROR");
    }

    private ModelProviderConnectionProbe.ProbeRequest request(String path, Duration timeout) {
        return requestAtPort(path, timeout, server.getAddress().getPort());
    }

    private static ModelProviderConnectionProbe.ProbeRequest requestAtPort(String path, Duration timeout, int port) {
        return new ModelProviderConnectionProbe.ProbeRequest(UUID.randomUUID(), "openai-compatible",
                "http://localhost:" + port + path, "secret/provider", timeout);
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
