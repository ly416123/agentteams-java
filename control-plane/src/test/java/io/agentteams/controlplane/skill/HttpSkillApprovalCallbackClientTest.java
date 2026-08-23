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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpSkillApprovalCallbackClientTest {
    private static final String DIGEST = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private HttpServer server;
    private ExecutorService serverExecutor;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        if (serverExecutor != null) serverExecutor.shutdownNow();
    }

    @Test
    void sendsOnlySafeApprovalMetadataAndMapsApproved() throws Exception {
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        server = start(exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes());
            assertThat(request).contains(skillId.toString(), versionId.toString(), DIGEST, "SANDBOX_TIMEOUT");
            assertThat(request).doesNotContain("manifest", "archive", "vendorDetail", "secret");
            respond(exchange, 200, "{\"status\":\"approved\",\"detail\":\"secret\"}");
        });

        SkillScanApprovalPort.ApprovalStatus status = client().onReviewRequired(
                new SkillScanApprovalPort.ApprovalRequest(skillId, versionId, "SANDBOX TIMEOUT", DIGEST));

        assertThat(status).isEqualTo(SkillScanApprovalPort.ApprovalStatus.APPROVED);
    }

    @Test
    void mapsFailureAndMalformedResponseToPendingWithoutLeakingResponse() throws Exception {
        server = start(exchange -> respond(exchange, 503, "secret callback response"));
        assertThat(client().onReviewRequired(request())).isEqualTo(SkillScanApprovalPort.ApprovalStatus.PENDING);

        server.stop(0);
        serverExecutor.shutdownNow();
        server = start(exchange -> respond(exchange, 200, "not-json"));
        assertThat(client().onReviewRequired(request())).isEqualTo(SkillScanApprovalPort.ApprovalStatus.PENDING);
    }

    @Test
    void rejectsUnsafeDigestBeforeMakingCallback() throws Exception {
        server = start(exchange -> {
            throw new AssertionError("unsafe metadata must not be sent");
        });

        assertThat(client().onReviewRequired(new SkillScanApprovalPort.ApprovalRequest(
                UUID.randomUUID(), UUID.randomUUID(), "REVIEW_REQUIRED", "not-a-digest")))
                .isEqualTo(SkillScanApprovalPort.ApprovalStatus.PENDING);
    }

    @Test
    void requiresRedirectsToBeDisabled() {
        assertThatThrownBy(() -> new HttpSkillApprovalCallbackClient(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
                URI.create("http://localhost/callback"), Duration.ofSeconds(1), 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("approval callback HTTP client must disable redirects");
    }

    private HttpSkillApprovalCallbackClient client() {
        return new HttpSkillApprovalCallbackClient(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("http://localhost:" + server.getAddress().getPort() + "/approval"),
                Duration.ofSeconds(1), 16 * 1024);
    }

    private SkillScanApprovalPort.ApprovalRequest request() {
        return new SkillScanApprovalPort.ApprovalRequest(UUID.randomUUID(), UUID.randomUUID(),
                "SANDBOX_TIMEOUT", DIGEST);
    }

    private HttpServer start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/approval", handler::handle);
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
