package io.agentteams.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentteams.manager.ManagerApplication;
import io.agentteams.manager.conversation.ConversationRuntimeConfiguration;
import io.agentteams.manager.security.ManagerAuthenticationFilter;
import io.agentteams.manager.security.ManagerIdentityTokenValidator;
import io.agentteams.manager.security.ManagerPrincipal;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * HTTP-level Conversation API acceptance test for both SSE reader implementations.
 *
 * <p>The upstream is a deterministic local HTTP/SSE server, not a mocked Manager or Runtime. This
 * keeps the test executable without QwenPaw while exercising the production QwenPaw protocol boundary.
 */
@Testcontainers(disabledWithoutDocker = true)
class VirtualThreadConversationIT {
    private static final String DATABASE_USER = "agentteams";
    private static final String DATABASE_PASSWORD = "agentteams-dev";
    private static final String TEST_TOKEN = "virtual-thread-conversation-it";
    private static final String TEST_SUBJECT = "manager-it";
    private static final String TEST_TENANT = "tenant-it";
    private static final String TEST_PROJECT_NAME = "project-it";
    private static final String TEST_TEAM = "team-it";
    private static final UUID TEST_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000721");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ManagerPrincipal PRINCIPAL = new ManagerPrincipal(
            TEST_SUBJECT, TEST_TENANT, TEST_PROJECT_ID.toString(), TEST_TEAM, Set.of("conversation:write"));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("agentteams")
            .withUsername(DATABASE_USER)
            .withPassword(DATABASE_PASSWORD);

    private static ConversationStub qwenPaw;

    @BeforeAll
    static void startUpstream() throws Exception {
        seedProjectAuthorizationTables();
        qwenPaw = new ConversationStub();
        qwenPaw.start();
    }

    @AfterAll
    static void stopUpstream() {
        if (qwenPaw != null) {
            qwenPaw.close();
        }
    }

    @Test
    void exercisesLongSseTerminalCancellationAndTimeoutWithBothExecutorModes() throws Exception {
        assertTimeoutPreemptively(Duration.ofMinutes(2), () -> {
            for (boolean virtualThreadsEnabled : new boolean[] { false, true }) {
                try (ConfigurableApplicationContext manager = startManager(virtualThreadsEnabled)) {
                    ConversationRuntimeConfiguration configuration = manager.getBean(
                            ConversationRuntimeConfiguration.class);
                    assertThat(configuration.virtualThreadsEnabled()).isEqualTo(virtualThreadsEnabled);

                    longSseReachesMessageTerminal(manager, virtualThreadsEnabled);
                    cancellationPublishesConversationTerminal(manager, virtualThreadsEnabled);
                    idleUpstreamTimesOut(manager, virtualThreadsEnabled);
                }
            }
        });
    }

    private static void longSseReachesMessageTerminal(ConfigurableApplicationContext manager, boolean virtual)
            throws Exception {
        UUID sessionId = UUID.randomUUID();
        createConversation(manager, sessionId, "create-terminal-" + virtual);
        postMessage(manager, sessionId, "message-terminal-" + virtual, "long-sse");

        HttpResponse<String> events = getEvents(manager, sessionId);
        assertThat(events.statusCode()).isEqualTo(200);
        assertThat(events.headers().firstValue("Content-Type").orElse("") )
                .startsWith("text/event-stream");
        assertThat(events.body())
                .contains("event: conversation.started")
                .contains("event: message.delta")
                .contains("event: message.completed")
                .doesNotContain("conversation.failed");
    }

    private static void cancellationPublishesConversationTerminal(ConfigurableApplicationContext manager,
            boolean virtual) throws Exception {
        UUID sessionId = UUID.randomUUID();
        createConversation(manager, sessionId, "create-cancel-" + virtual);
        postMessage(manager, sessionId, "message-cancel-" + virtual, "cancel-sse");
        assertThat(qwenPaw.awaitCancelScenarioStarted()).isTrue();

        HttpResponse<String> cancelled = postCancel(manager, sessionId, "cancel-" + virtual);
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(cancelled.body()).path("status").asText()).isEqualTo("CANCELLED");

        HttpResponse<String> events = getEvents(manager, sessionId);
        assertThat(events.statusCode()).isEqualTo(200);
        assertThat(events.body()).contains("event: conversation.cancelled");
    }

    private static void idleUpstreamTimesOut(ConfigurableApplicationContext manager, boolean virtual)
            throws Exception {
        UUID sessionId = UUID.randomUUID();
        createConversation(manager, sessionId, "create-timeout-" + virtual);
        postMessage(manager, sessionId, "message-timeout-" + virtual, "timeout-sse");

        HttpResponse<String> events = getEvents(manager, sessionId);
        assertThat(events.statusCode()).isEqualTo(200);
        assertThat(events.body())
                .contains("event: conversation.failed")
                .contains("CONVERSATION_TIMEOUT");
    }

    private static ConfigurableApplicationContext startManager(boolean virtualThreadsEnabled) {
        return new SpringApplicationBuilder(ManagerApplication.class, TestSecurityConfiguration.class)
                .properties(
                        "spring.main.web-application-type=servlet",
                        "spring.main.banner-mode=off",
                        "spring.main.allow-bean-definition-overriding=true",
                        "server.port=0",
                        "server.address=127.0.0.1",
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + DATABASE_USER,
                        "spring.datasource.password=" + DATABASE_PASSWORD,
                        "AGENTTEAMS_CONTROL_PLANE_URL=http://127.0.0.1:1",
                        "AGENTTEAMS_CONVERSATION_RUNTIME=qwenpaw",
                        "AGENTTEAMS_CONVERSATION_QWENPAW_ENDPOINT=http://127.0.0.1:" + qwenPaw.port(),
                        "AGENTTEAMS_CONVERSATION_QWENPAW_AGENT_ID=integration-test",
                        "AGENTTEAMS_CONVERSATION_CONNECT_TIMEOUT_MS=1000",
                        "AGENTTEAMS_CONVERSATION_REQUEST_TIMEOUT_MS=250",
                        "AGENTTEAMS_CONVERSATION_MAX_CONCURRENT_REQUESTS=2",
                        "AGENTTEAMS_CONVERSATION_MAX_EVENTS_PER_SESSION=100",
                        "AGENTTEAMS_CONVERSATION_MAX_SESSIONS=100",
                        "agentteams.concurrency.virtual-threads.enabled=" + virtualThreadsEnabled)
                .run();
    }

    private static void createConversation(ConfigurableApplicationContext manager, UUID sessionId,
            String idempotencyKey) throws Exception {
        HttpResponse<String> response = send(manager, HttpRequest.newBuilder(uri(manager,
                "/api/v1/conversations"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"sessionId":"%s","project":"%s","team":"%s","worker":"worker-it","task":"task-it"}
                        """.formatted(sessionId, TEST_PROJECT_NAME, TEST_TEAM)))
                .build());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    private static void postMessage(ConfigurableApplicationContext manager, UUID sessionId,
            String idempotencyKey, String content) throws Exception {
        HttpResponse<String> response = send(manager, HttpRequest.newBuilder(uri(manager,
                "/api/v1/conversations/" + sessionId + "/messages"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"" + content + "\"}"))
                .build());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private static HttpResponse<String> postCancel(ConfigurableApplicationContext manager, UUID sessionId,
            String idempotencyKey) throws Exception {
        return send(manager, HttpRequest.newBuilder(uri(manager,
                "/api/v1/conversations/" + sessionId + "/cancel"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build());
    }

    private static HttpResponse<String> getEvents(ConfigurableApplicationContext manager, UUID sessionId)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(manager,
                "/api/v1/conversations/" + sessionId + "/events?after=0"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .GET()
                .build();
        return send(manager, request);
    }

    private static HttpResponse<String> send(ConfigurableApplicationContext manager, HttpRequest request)
            throws Exception {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static URI uri(ConfigurableApplicationContext manager, String path) {
        int port = ((WebServerApplicationContext) manager).getWebServer().getPort();
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static void seedProjectAuthorizationTables() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), DATABASE_USER, DATABASE_PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS projects (
                        id UUID PRIMARY KEY,
                        tenant_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'ACTIVE',
                        created_by TEXT NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL,
                        updated_at TIMESTAMPTZ NOT NULL,
                        version BIGINT NOT NULL DEFAULT 0
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS project_memberships (
                        tenant_id TEXT NOT NULL,
                        project_id UUID NOT NULL,
                        subject TEXT NOT NULL,
                        role TEXT NOT NULL DEFAULT 'DEVELOPER',
                        status TEXT NOT NULL DEFAULT 'ACTIVE',
                        created_at TIMESTAMPTZ NOT NULL,
                        updated_at TIMESTAMPTZ NOT NULL,
                        version BIGINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (tenant_id, project_id, subject)
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO projects (id, tenant_id, name, status, created_by, created_at, updated_at)
                    VALUES ('%s', '%s', '%s', 'ACTIVE', '%s', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE', name = '%s'
                    """.formatted(TEST_PROJECT_ID, TEST_TENANT, TEST_PROJECT_NAME, TEST_SUBJECT,
                    TEST_PROJECT_NAME));
            statement.executeUpdate("""
                    INSERT INTO project_memberships (tenant_id, project_id, subject, role, status,
                                                     created_at, updated_at)
                    VALUES ('%s', '%s', '%s', 'DEVELOPER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (tenant_id, project_id, subject) DO UPDATE
                       SET role = 'DEVELOPER', status = 'ACTIVE'
                    """.formatted(TEST_TENANT, TEST_PROJECT_ID, TEST_SUBJECT));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestSecurityConfiguration {
        @Bean(name = "managerIdentityTokenValidator")
        ManagerIdentityTokenValidator managerIdentityTokenValidator() {
            return token -> TEST_TOKEN.equals(token) ? Optional.of(PRINCIPAL) : Optional.empty();
        }

        @Bean(name = "managerAuthenticationFilter")
        ManagerAuthenticationFilter managerAuthenticationFilter(ManagerIdentityTokenValidator validator) {
            return new ManagerAuthenticationFilter(validator);
        }
    }

    private static final class ConversationStub implements AutoCloseable {
        private final HttpServer server;
        private final CountDownLatch cancelScenarioStarted = new CountDownLatch(1);

        private ConversationStub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/console/chat", this::chat);
            server.createContext("/api/console/chat/stop", this::stop);
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "conversation-it-qwenpaw");
                thread.setDaemon(true);
                return thread;
            }));
        }

        private void start() {
            server.start();
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private boolean awaitCancelScenarioStarted() throws InterruptedException {
            return cancelScenarioStarted.await(5, TimeUnit.SECONDS);
        }

        private void chat(HttpExchange exchange) throws IOException {
            String request = readBody(exchange.getRequestBody());
            String content = MAPPER.readTree(request).path("input").path(0).path("content")
                    .path(0).path("text").asText();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (exchange) {
                var output = exchange.getResponseBody();
                write(output, "event: data\ndata: {\"type\":\"text\",\"delta\":true,\"text\":\"part-1\"}\n\n");
                if (content.startsWith("cancel")) {
                    cancelScenarioStarted.countDown();
                    sleep(5_000);
                    return;
                }
                if (content.startsWith("timeout")) {
                    sleep(1_500);
                    return;
                }
                sleep(100);
                write(output,
                        "event: data\ndata: {\"type\":\"response\",\"status\":\"completed\","
                                + "\"object\":\"response\",\"text\":\"done\"}\n\n");
            } catch (IOException ignored) {
                // The production cancel path is expected to close the in-flight SSE response.
            }
        }

        private void stop(HttpExchange exchange) throws IOException {
            readBody(exchange.getRequestBody());
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (exchange) {
                exchange.getResponseBody().write(body);
            }
        }

        private static String readBody(InputStream input) throws IOException {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        private static void write(java.io.OutputStream output, String value) throws IOException {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
