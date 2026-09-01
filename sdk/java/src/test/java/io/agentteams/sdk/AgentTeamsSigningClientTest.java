package io.agentteams.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTeamsSigningClientTest {
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void signsEveryRequestWithApplicationAndExternalUserContext() {
        AtomicReference<java.net.http.HttpHeaders> headers = new AtomicReference<>();
        server.createContext("/api/v1/tasks", exchange -> {
            headers.set(java.net.http.HttpHeaders.of(exchange.getRequestHeaders(), (name, value) -> true));
            respond(exchange, 200, "{\"id\":\"00000000-0000-0000-0000-000000000001\",\"title\":\"Demo\",\"description\":\"Task\",\"phase\":\"DRAFT\",\"priority\":0,\"version\":0}");
        });

        AgentTeamsClient client = new AgentTeamsClient(baseUrl, "ak-1", "secret-1", "org-1")
                .asUser("user-1");

        assertThat(client.getTask(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))).isNotNull();
        assertThat(headers.get().firstValue("Authorization")).hasValueSatisfying(value -> assertThat(value).startsWith("AT-HMAC-SHA256 Credential=ak-1"));
        assertThat(headers.get().firstValue("X-AT-Organization-Id")).hasValue("org-1");
        assertThat(headers.get().firstValue("X-AT-User-Id")).hasValue("user-1");
        assertThat(headers.get().firstValue("X-AT-Timestamp")).isPresent();
        assertThat(headers.get().firstValue("X-AT-Nonce")).isPresent();
        assertThat(headers.get().firstValue("X-AT-Content-SHA256")).hasValue(AgentTeamsClient.sha256(""));
        assertThat(headers.get().firstValue("X-AT-Signature")).isPresent();
    }

    @Test
    void keepsWriteIdempotencyKeyAndRequestHashStableAcrossExplicitRetries() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/api/v1/projects", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo("idem-1");
            String firstHash = exchange.getRequestHeaders().getFirst("X-AT-Content-SHA256");
            if (attempts.incrementAndGet() == 1) {
                respond(exchange, 503, "{\"code\":\"TEMPORARY\",\"message\":\"retry\"}");
            } else {
                assertThat(exchange.getRequestHeaders().getFirst("X-AT-Content-SHA256")).isEqualTo(firstHash);
                respond(exchange, 201, "{\"id\":\"00000000-0000-0000-0000-000000000010\",\"name\":\"Demo\"}");
            }
        });

        AgentTeamsClient client = new AgentTeamsClient(baseUrl, "ak-1", "secret-1", "org-1", 1, 0)
                .asUser("user-1");
        client.createProject(new AgentTeamsClient.CreateProjectRequest("Demo"), "idem-1", true);

        assertThat(attempts).hasValue(2);
    }

    @Test
    void exposesProvisioningOperationsUnderTheCurrentExternalUser() {
        server.createContext("/api/v1/provisioning/users", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            respond(exchange, 200, "{\"externalUserId\":\"user-2\",\"status\":\"ACTIVE\"}");
        });
        server.createContext("/api/v1/provisioning/users/user-2/memberships", exchange ->
                respond(exchange, 200, "[{\"projectId\":\"project-1\",\"role\":\"DEVELOPER\"}]"));

        AgentTeamsClient.Provisioning provisioning = new AgentTeamsClient(baseUrl, "ak-1", "secret-1", "org-1")
                .asUser("user-1").provisioning();
        assertThat(provisioning.initializeUser(new AgentTeamsClient.ProvisioningRequest("user-2", "Alice"), "idem-2", false).status())
                .isEqualTo("ACTIVE");
        assertThat(provisioning.listMemberships("user-2")).hasSize(1);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
