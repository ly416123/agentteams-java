package io.agentteams.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTeamsClientTest {
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
    void sendsSignatureAndIdempotencyHeadersForProjectCreation() {
        server.createContext("/api/v1/projects", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("AT-HMAC-SHA256 Credential=ak-1");
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).startsWith("sdk-");
            respond(exchange, 201, "{\"id\":\"00000000-0000-0000-0000-000000000010\",\"tenantId\":\"tenant-1\",\"name\":\"Demo\",\"status\":\"ACTIVE\",\"createdBy\":\"user-1\"}");
        });

        AgentTeamsClient client = new AgentTeamsClient(baseUrl, "ak-1", "secret-1", "org-1").asUser("user-1");
        AgentTeamsClient.Project project = client.createProject(new AgentTeamsClient.CreateProjectRequest("Demo"));

        assertThat(project.id()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000010"));
        assertThat(project.name()).isEqualTo("Demo");
    }

    @Test
    void retriesGetButOnlyRetriesWriteWhenExplicitlySafe() {
        AtomicInteger getAttempts = new AtomicInteger();
        server.createContext("/api/v1/tasks/", exchange -> {
            if (getAttempts.incrementAndGet() == 1) {
                respond(exchange, 503, "{\"code\":\"TEMPORARY\",\"message\":\"retry\"}");
            } else {
                respond(exchange, 200, taskJson());
            }
        });
        AgentTeamsClient client = new AgentTeamsClient(baseUrl, "ak-1", "secret-1", "org-1", 2, 0).asUser("user-1");

        assertThat(client.getTask(UUID.fromString("00000000-0000-0000-0000-000000000001")).id())
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(getAttempts).hasValue(2);
    }

    @Test
    void mapsStructuredConflictWithoutReturningRawResponseBody() {
        server.createContext("/api/v1/tasks/", exchange ->
                respond(exchange, 409, "{\"code\":\"VERSION_CONFLICT\",\"message\":\"资源版本冲突\",\"correlationId\":\"corr-1\",\"details\":{\"expectedVersion\":3}}"));
        AgentTeamsClient client = new AgentTeamsClient(baseUrl, "ak-1", "secret-1", "org-1", 0, 0).asUser("user-1");

        assertThatThrownBy(() -> client.cancelTask(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new AgentTeamsClient.LifecycleRequest(3L, null, null)))
                .isInstanceOf(AgentTeamsClient.ApiErrorException.class)
                .hasMessage("资源版本冲突")
                .satisfies(error -> {
                    AgentTeamsClient.ApiErrorException apiError = (AgentTeamsClient.ApiErrorException) error;
                    assertThat(apiError.status()).isEqualTo(409);
                    assertThat(apiError.code()).isEqualTo("VERSION_CONFLICT");
                    assertThat(apiError.correlationId()).isEqualTo("corr-1");
                });
    }

    @Test
    void readsTaskProgressResultAndProcessEvents() {
        server.createContext("/api/v1/tasks/00000000-0000-0000-0000-000000000001/runs", exchange ->
                respond(exchange, 200, "[{\"id\":\"00000000-0000-0000-0000-000000000002\",\"taskId\":\"00000000-0000-0000-0000-000000000001\",\"status\":\"SUCCEEDED\",\"version\":1,\"resultStatus\":\"SUCCEEDED\",\"resultSummary\":\"done\"}]"));
        server.createContext("/api/v1/tasks/00000000-0000-0000-0000-000000000001/runs/00000000-0000-0000-0000-000000000002/progress", exchange ->
                respond(exchange, 200, "{\"phase\":\"EXECUTION\",\"completed\":2,\"total\":4,\"progress\":50,\"waitingReason\":\"\"}"));
        server.createContext("/api/v1/tasks/00000000-0000-0000-0000-000000000001/runs/00000000-0000-0000-0000-000000000002/result", exchange ->
                respond(exchange, 200, "{\"taskId\":\"00000000-0000-0000-0000-000000000001\",\"runId\":\"00000000-0000-0000-0000-000000000002\",\"status\":\"SUCCEEDED\",\"summary\":\"done\",\"artifacts\":[]}"));
        server.createContext("/api/v1/tasks/00000000-0000-0000-0000-000000000001/runs/00000000-0000-0000-0000-000000000002/process-events", exchange ->
                respond(exchange, 200, "[{\"eventId\":\"00000000-0000-0000-0000-000000000003\",\"taskId\":\"00000000-0000-0000-0000-000000000001\",\"runId\":\"00000000-0000-0000-0000-000000000002\",\"sequence\":1,\"eventType\":\"PROGRESS\",\"visibility\":\"REQUESTER\",\"occurredAt\":\"2026-08-31T00:00:00Z\",\"correlationId\":\"corr-1\",\"payload\":\"{\\\"progress\\\":50}\"}]"));

        AgentTeamsClient client = new AgentTeamsClient(baseUrl, "ak-1", "secret-1", "org-1").asUser("user-1");

        assertThat(client.listTaskRuns(UUID.fromString("00000000-0000-0000-0000-000000000001"))).hasSize(1);
        assertThat(client.getTaskProgress(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002")).progress()).isEqualTo(50);
        assertThat(client.getTaskResult(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002")).status()).isEqualTo("SUCCEEDED");
        assertThat(client.listTaskProcessEvents(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"), 0)).hasSize(1);
    }

    @Test
    void createsAndQueuesTaskWithIdempotencyAndExpectedVersion() throws Exception {
        UUID taskId = UUID.fromString("00000000-0000-0000-0000-000000000021");
        server.createContext("/api/v1/tasks", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer token-1");
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo("task-create-1");
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("\"taskType\":\"CHAT\"");
            respond(exchange, 201, taskJsonFor(taskId, "DRAFT", 0));
        });
        server.createContext("/api/v1/tasks/" + taskId + "/queue", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo("task-queue-1");
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("\"expectedVersion\":0");
            respond(exchange, 200, taskJsonFor(taskId, "QUEUED", 1));
        });

        AgentTeamsClient client = new AgentTeamsClient(baseUrl, () -> "token-1");
        AgentTeamsClient.Task created = client.createTask(
                new AgentTeamsClient.CreateTaskRequest("chat", "hello", new ObjectMapper().createObjectNode()
                        .put("projectId", "project-a"), "user-1", "corp-agent", "CHAT"),
                "task-create-1", false);
        AgentTeamsClient.Task queued = client.queueTask(taskId, created.version(), "task-queue-1", false);

        assertThat(created.phase()).isEqualTo("DRAFT");
        assertThat(queued.phase()).isEqualTo("QUEUED");
        assertThat(queued.version()).isEqualTo(1);
    }

    @Test
    void createsPublishesAndDeploysAgentSpecWithOidcHeaders() {
        UUID specId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID agentId = UUID.fromString("00000000-0000-0000-0000-000000000012");
        server.createContext("/api/v1/agent-specs", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer token-1");
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isNotBlank();
            if ("POST".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getPath().equals("/api/v1/agent-specs")) {
                respond(exchange, 201, "{\"id\":\"" + specId + "\",\"name\":\"leader\",\"runtime\":\"qwenpaw\",\"modelProvider\":\"default\",\"modelName\":\"qwen\",\"teamRef\":\"team-a\",\"workerType\":\"LEADER\",\"desiredState\":\"RUNNING\",\"lifecycleStatus\":\"DRAFT\",\"spec\":\"{}\",\"version\":0}");
            } else {
                respond(exchange, 200, "{\"id\":\"" + specId + "\",\"name\":\"leader\",\"runtime\":\"qwenpaw\",\"modelProvider\":\"default\",\"modelName\":\"qwen\",\"teamRef\":\"team-a\",\"workerType\":\"LEADER\",\"desiredState\":\"RUNNING\",\"lifecycleStatus\":\"PUBLISHED\",\"spec\":\"{}\",\"version\":1}");
            }
        });
        server.createContext("/api/v1/agent-specs/00000000-0000-0000-0000-000000000011/deployments/00000000-0000-0000-0000-000000000012", exchange ->
                deployResponse(exchange, agentId));
        server.createContext("/api/v1/agents", exchange ->
                respond(exchange, 201, "{\"id\":\"" + agentId + "\",\"name\":\"leader\",\"workerType\":\"LEADER\",\"phase\":\"PROVISIONING\",\"runtime\":\"qwenpaw\",\"version\":0}"));

        AgentTeamsClient client = new AgentTeamsClient(baseUrl, () -> "token-1");
        AgentTeamsClient.Agent agent = client.createAgent(
                new AgentTeamsClient.CreateAgentRequest("leader", "qwenpaw", "LEADER", null, null),
                "project-a", "agent-create-1");
        AgentTeamsClient.AgentSpec spec = client.createAgentSpec(
                new AgentTeamsClient.CreateAgentSpecRequest("leader", "qwenpaw", "default", "qwen", "team-a",
                        "RUNNING", "LEADER", null), "project-a", "spec-create-1");
        assertThat(client.publishAgentSpec(specId, "project-a", "spec-publish-1").lifecycleStatus()).isEqualTo("PUBLISHED");
        assertThat(client.deployAgentSpec(specId, agentId, "project-a", "corp-agent").agentId()).isEqualTo(agentId);
        assertThat(agent.id()).isEqualTo(agentId);
        assertThat(spec.id()).isEqualTo(specId);
    }

    private static void deployResponse(HttpExchange exchange, UUID agentId) throws IOException {
        assertThat(exchange.getRequestHeaders().getFirst("X-Actor")).isEqualTo("corp-agent");
        respond(exchange, 200, "{\"bindingId\":\"00000000-0000-0000-0000-000000000013\",\"agentId\":\"" + agentId + "\",\"snapshotId\":\"00000000-0000-0000-0000-000000000014\",\"configVersion\":1,\"eventId\":\"00000000-0000-0000-0000-000000000015\",\"phase\":\"PENDING\"}");
    }

    private static String taskJson() {
        return "{\"id\":\"00000000-0000-0000-0000-000000000001\",\"title\":\"Demo\","
                + "\"description\":\"Task\",\"phase\":\"DRAFT\",\"priority\":0,"
                + "\"createdAt\":\"2026-08-31T00:00:00Z\",\"updatedAt\":\"2026-08-31T00:00:00Z\",\"version\":0}";
    }

    private static String taskJsonFor(UUID taskId, String phase, long version) {
        return "{\"id\":\"" + taskId + "\",\"title\":\"chat\",\"description\":\"hello\","
                + "\"phase\":\"" + phase + "\",\"priority\":0,"
                + "\"createdAt\":\"2026-08-31T00:00:00Z\",\"updatedAt\":\"2026-08-31T00:00:00Z\","
                + "\"version\":" + version + "}";
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
