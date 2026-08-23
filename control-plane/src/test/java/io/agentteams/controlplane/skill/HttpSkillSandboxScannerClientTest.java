package io.agentteams.controlplane.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpSkillSandboxScannerClientTest {
    private HttpServer server;
    private ExecutorService serverExecutor;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        if (serverExecutor != null) serverExecutor.shutdownNow();
    }

    @Test
    void postsManifestAndRedactsProviderDetail() throws Exception {
        server = start(exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes());
            assertThat(request).contains("manifestJson", "archiveBase64");
            respond(exchange, 200, "{\"decision\":\"CLEAN\",\"classification\":\"vendor clean\","
                    + "\"detail\":\"secret provider path\"}");
        });

        HttpSkillSandboxScannerClient client = client(Duration.ofSeconds(1));
        SkillSandboxScannerClient.ScanResult result = client.scan(
                new SkillSandboxScannerClient.ScanRequest("{}", new byte[] {1, 2, 3}));

        assertThat(result.decision()).isEqualTo(SkillSandboxScannerClient.Decision.CLEAN);
        assertThat(result.classification()).isEqualTo("vendor_clean");
        assertThat(result.detail()).isNull();
    }

    @Test
    void mapsHttpFailureToUnavailableWithoutExposingResponseBody() throws Exception {
        server = start(exchange -> respond(exchange, 503, "secret provider response"));
        HttpSkillSandboxScannerClient client = client(Duration.ofSeconds(1));

        assertThatThrownBy(() -> client.scan(new SkillSandboxScannerClient.ScanRequest("{}", null)))
                .isInstanceOf(SkillSandboxScannerClient.UnavailableException.class)
                .hasMessage("sandbox HTTP service returned a non-success status")
                .hasMessageNotContaining("secret provider response");
    }

    @Test
    void mapsMalformedResponseToInvalidResultAtExistingExternalBoundary() throws Exception {
        server = start(exchange -> respond(exchange, 200, "not-json"));
        ConfiguredExternalSkillSandboxScanner scanner = new ConfiguredExternalSkillSandboxScanner(
                client(Duration.ofSeconds(1)), Duration.ofSeconds(1));

        ExternalSkillSandboxScanner.SandboxScanResult result = scanner.scan(
                new ExternalSkillSandboxScanner.SandboxScanRequest("{}", null));
        scanner.close();

        assertThat(result.decision()).isEqualTo(ExternalSkillSandboxScanner.Decision.REVIEW_REQUIRED);
        assertThat(result.classification()).isEqualTo(ExternalSkillSandboxScanner.SANDBOX_INVALID_RESULT);
        assertThat(result.vendorDetail()).isNull();
    }

    @Test
    void mapsProviderTimeoutToExistingTimeoutClassification() throws Exception {
        server = start(exchange -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"decision\":\"CLEAN\",\"classification\":\"clean\"}");
        });
        ConfiguredExternalSkillSandboxScanner scanner = new ConfiguredExternalSkillSandboxScanner(
                client(Duration.ofMillis(20)), Duration.ofSeconds(1));

        ExternalSkillSandboxScanner.SandboxScanResult result = scanner.scan(
                new ExternalSkillSandboxScanner.SandboxScanRequest("{}", null));
        scanner.close();

        assertThat(result.classification()).isEqualTo(ExternalSkillSandboxScanner.SANDBOX_TIMEOUT);
    }

    private HttpSkillSandboxScannerClient client(Duration timeout) {
        return new HttpSkillSandboxScannerClient(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("http://localhost:" + server.getAddress().getPort() + "/scan"),
                timeout, 64 * 1024);
    }

    private HttpServer start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/scan", handler::handle);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes();
        exchange.sendResponseHeaders(status, bytes.length);
        try (exchange) {
            exchange.getResponseBody().write(bytes);
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
