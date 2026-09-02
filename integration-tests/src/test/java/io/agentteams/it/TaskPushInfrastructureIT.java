package io.agentteams.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentteams.controlplane.ControlPlaneApplication;
import io.agentteams.gateway.AgentGatewayApplication;
import io.agentteams.gateway.AgentGatewayGrpcServer;
import io.agentteams.gateway.AgentStatePort;
import io.agentteams.gateway.JdbcAgentStateStore;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.nats.client.Connection;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.agentteams.contracts.v1.ProtocolVersion;
import io.agentteams.contracts.v1.ConfigChanged;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Acceptance test for the real PostgreSQL/NATS/MinIO task-push path. */
@Testcontainers(disabledWithoutDocker = true)
class TaskPushInfrastructureIT {

    private static final String MINIO_VERSION = "RELEASE.2024-11-07T00-52-20Z";
    private static final String DATABASE_USER = "agentteams";
    private static final String DATABASE_PASSWORD = "agentteams-dev";
    private static final String STORAGE_ACCESS_KEY = "minioadmin";
    private static final String STORAGE_SECRET_KEY = "minioadmin";
    private static final String TEST_TENANT = "tenant-a";
    private static final String TEST_PROJECT = "project-a";
    private static final String TEST_TEAM = "team-a";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("agentteams")
            .withUsername(DATABASE_USER)
            .withPassword(DATABASE_PASSWORD);

    @Container
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.10-alpine")
            .withCommand("-js")
            .withExposedPorts(4222)
            .waitingFor(Wait.forListeningPort());

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(
            "minio/minio:" + MINIO_VERSION)
            .withCommand("server", "/data", "--console-address", ":9001")
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withExposedPorts(9000, 9001)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    private static ConfigurableApplicationContext controlPlane;
    private static ConfigurableApplicationContext gateway;
    private static ConfigurableApplicationContext gatewayReplicaTwo;
    private static Connection natsConnection;

    @BeforeAll
    static void startInfrastructureApplications() throws Exception {
        natsConnection = Nats.connect(natsUrl());
        createStreams(natsConnection.jetStreamManagement());
        createBucket();

        controlPlane = new SpringApplicationBuilder(ControlPlaneApplication.class)
                .run(commandLineProperties(controlPlaneProperties()));
        gateway = new SpringApplicationBuilder(AgentGatewayApplication.class)
                .run(commandLineProperties(gatewayProperties("replica-1")));
        gatewayReplicaTwo = new SpringApplicationBuilder(AgentGatewayApplication.class)
                .run(commandLineProperties(gatewayProperties("replica-2")));
    }

    @AfterAll
    static void stopInfrastructureApplications() {
        if (gateway != null) {
            gateway.close();
        }
        if (gatewayReplicaTwo != null) {
            gatewayReplicaTwo.close();
        }
        if (controlPlane != null) {
            controlPlane.close();
        }
        if (natsConnection != null) {
            try {
                natsConnection.close();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @org.junit.jupiter.api.Test
    void startsRealControlPlaneForTheInfrastructurePath() {
        assertNotNull(controlPlane, "Control Plane context must be started for infrastructure E2E");
        assertNotNull(gateway, "Gateway context must be started for infrastructure E2E");
    }

    @org.junit.jupiter.api.Test
    void pushesTaskThroughRealInfrastructureAndReplaysUnacknowledgedCommand() throws Exception {
        assertTrue(!gateway.getBeansOfType(DataSource.class).isEmpty(),
                "Gateway must expose a JDBC DataSource for durable agent state and command replay");
        assertEquals(JdbcAgentStateStore.class, gateway.getBean(AgentStatePort.class).getClass(),
                "Gateway must use the JDBC agent state projection when a DataSource is configured");
        UUID agentId = createAgent();
        FakeAgent firstConnection = FakeAgent.connect("127.0.0.1", gatewayPort(), agentId.toString(),
                ProtocolVersion.newBuilder().setMajor(2).setMinor(3).build());
        try {
            firstConnection.awaitReady();
            UUID taskId = createAndQueueTask();
            awaitAgentPhase(agentId, "READY");
            awaitTaskPhase(taskId, "ASSIGNED");
            awaitTrue(() -> gatewayCommandCount(agentId) > 0,
                    "gateway command should be persisted: " + databaseSnapshot(taskId));
            TaskAssignedAssignment assignment = awaitAssignment(taskId, firstConnection);
            assertEquals(1, countRows("task_attempts", taskId));
            assertEquals(1, countRows("task_assignments", taskId));
            assertEquals(1, countRows("agent_leases",
                    UUID.fromString(assignment.task().getMetadata().getLeaseId())));

            firstConnection.closeWithoutAcknowledging();
            FakeAgent reconnected = FakeAgent.connect("127.0.0.1", gatewayPort(), agentId.toString(),
                    ProtocolVersion.newBuilder().setMajor(2).setMinor(3).build());
            try {
                reconnected.awaitReady();
                io.agentteams.contracts.v1.TaskAssigned replayed = reconnected.awaitTaskAssigned(taskId.toString());
                assertEquals(assignment.task().getMetadata().getEventId(), replayed.getMetadata().getEventId(),
                        "the durable command store must replay the unacknowledged assignment");
                reconnected.acknowledge(replayed);

                byte[] artifact = "real infrastructure artifact".getBytes(StandardCharsets.UTF_8);
                String sha256 = sha256(artifact);
                String objectKey = "tasks/" + taskId + "/attempts/" + assignment.task().getMetadata().getAttemptId()
                        + "/artifacts/result.txt";
                uploadArtifact(objectKey, artifact);

                reconnected.accept(assignment.task());
                reconnected.progress(assignment.task());
                reconnected.heartbeat(assignment.task());
                String completionEventId = UUID.randomUUID().toString();
                reconnected.completeWithEventId(assignment.task(), completionEventId, "result.txt",
                        objectKey, sha256, artifact.length);
                awaitTaskPhase(taskId, "SUCCEEDED", reconnected);

                assertEquals(1, countArtifacts(taskId), "the completion event must persist one artifact");
                assertEquals(objectKey, artifactStorageKey(taskId));
                assertEquals(artifact.length, artifactSize(taskId));
                assertEquals(sha256, artifactSha256(taskId));
                assertEquals("AVAILABLE", artifactStatus(taskId));
                assertEquals(assignment.task().getMetadata().getAttemptId(), artifactAttemptId(taskId));
                assertEquals("real infrastructure artifact", downloadArtifact(objectKey));

                reconnected.completeWithEventId(assignment.task(), completionEventId, "result.txt",
                        objectKey, sha256, artifact.length);
                awaitTrue(() -> countArtifacts(taskId) == 1, "duplicate completion must remain idempotent");
                assertEquals(1, countDomainEvents(completionEventId));
            } finally {
                reconnected.close();
            }
        } finally {
            firstConnection.close();
        }
    }

    @org.junit.jupiter.api.Test
    void fansOutConfigChangedToBothGatewayConsumersAndDeduplicatesTheCommand() throws Exception {
        UUID agentId = createAgent();
        FakeAgent agent = FakeAgent.connect("127.0.0.1", gatewayPort(), agentId.toString(),
                ProtocolVersion.newBuilder().setMajor(2).setMinor(3).build());
        try {
            agent.awaitReady();
            String snapshotBody = """
                    {"subject":"fake-agent-config","manifest":{"model":"fanout-test","provider_id":"fake"},"actor":"integration-test"}
                    """;
            com.fasterxml.jackson.databind.JsonNode snapshot = postJson(
                    "/api/v1/config/snapshots", "config-snapshot-" + UUID.randomUUID(), snapshotBody);
            UUID snapshotId = UUID.fromString(snapshot.path("id").asText());
            long configVersion = snapshot.path("version").asLong();
            byte[] fileContent = "{\"temperature\":0.1}".getBytes(StandardCharsets.UTF_8);
            String fileSha = sha256(fileContent);
            com.fasterxml.jackson.databind.JsonNode prepared = postJson(
                    "/api/v1/config/snapshots/" + snapshotId + "/files/uploads",
                    "config-upload-" + UUID.randomUUID(),
                    "{\"path\":\"models/default.json\",\"contentType\":\"application/json\","
                            + "\"sha256\":\"" + fileSha + "\",\"sizeBytes\":" + fileContent.length
                            + ",\"expirySeconds\":900}");
            HttpResponse<String> upload = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                    .uri(URI.create(prepared.path("uploadUrl").asText()))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(fileContent)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(upload.statusCode() >= 200 && upload.statusCode() < 300,
                    "config file upload failed: " + upload.statusCode());
            postJson("/api/v1/config/snapshots/" + snapshotId + "/files/uploads/"
                            + prepared.path("uploadId").asText() + "/complete",
                    "config-complete-" + UUID.randomUUID(), "{}");
            com.fasterxml.jackson.databind.JsonNode deployment = postJson(
                    "/api/v1/config/snapshots/" + snapshotId + "/agents/" + agentId,
                    "config-deploy-" + UUID.randomUUID(), "{}");

            ConfigChanged changed = agent.awaitConfigChanged(configVersion);
            assertEquals(1, changed.getFilesCount());
            assertEquals("models/default.json", changed.getFiles(0).getPath());
            assertEquals(fileSha, changed.getFiles(0).getSha256());
            agent.acknowledge(changed);

            awaitTrue(() -> gatewayCommandCount(agentId) == 1,
                    "both Gateway consumers must deduplicate to one durable command");
            awaitTrue(() -> configConsumerDelivered("agent-gateway-config-replica-1")
                            && configConsumerDelivered("agent-gateway-config-replica-2"),
                    "both Gateway durable consumers must receive and acknowledge the event");
            awaitTrue(() -> acknowledgedSequence(agentId) == changed.getMetadata().getSequence(),
                    "the active Gateway connection must advance the shared ACK cursor");

            assertEquals(deployment.path("eventId").asText(), changed.getMetadata().getEventId());
            assertEquals(1, gatewayCommandCount(agentId));
            assertEquals(1, gatewayDeliveryCount(agentId, changed.getMetadata().getSequence()),
                    "only the current connection may deliver the command to the Agent");
            agent.applyConfig(changed);
            awaitTrue(() -> "APPLIED".equals(jdbc().queryForObject(
                    "SELECT phase FROM config_apply_records WHERE binding_id = ? AND snapshot_id = ?",
                    String.class, UUID.fromString(deployment.path("bindingId").asText()), snapshotId)),
                    "Control Plane must persist ConfigApplied");
        } finally {
            agent.close();
        }
    }

    private record TaskAssignedAssignment(io.agentteams.contracts.v1.TaskAssigned task) {
    }

    private static TaskAssignedAssignment awaitAssignment(UUID taskId, FakeAgent agent) throws InterruptedException {
        return new TaskAssignedAssignment(agent.awaitTaskAssigned(taskId.toString()));
    }

    private static UUID createAgent() throws Exception {
        String body = """
                {"name":"%s","runtime":"fake-agent","capabilities":{"tasks":"1"},"metadata":{}}
                """.formatted("infrastructure-agent-" + UUID.randomUUID());
        UUID agentId = UUID.fromString(postJson("/api/v1/agents", "agent-create-" + UUID.randomUUID(), body)
                .path("id").asText());
        bindInfrastructureResourceScope("WORKER", agentId);
        return agentId;
    }

    private static UUID createAndQueueTask() throws Exception {
        String body = """
                {"title":"real task push","description":"infrastructure acceptance","spec":{"taskType":"demo","requiredCapabilities":["tasks"]}}
                """;
        UUID taskId = UUID.fromString(postJson("/api/v1/tasks", "task-create-" + UUID.randomUUID(), body)
                .path("id").asText());
        bindInfrastructureResourceScope("TASK", taskId);
        postJson("/api/v1/tasks/" + taskId + "/queue", "task-queue-" + UUID.randomUUID(), "{}");
        return taskId;
    }

    /** The infrastructure test uses trusted unauthenticated APIs, so it must bind fixture scopes explicitly. */
    private static void bindInfrastructureResourceScope(String resourceType, UUID resourceId) {
        jdbc().update("""
                INSERT INTO resource_scopes(resource_type, resource_id, tenant_id, project_id, team,
                                            created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, resourceType, resourceId, TEST_TENANT, TEST_PROJECT, TEST_TEAM);
    }

    private static com.fasterxml.jackson.databind.JsonNode postJson(String path, String idempotencyKey,
            String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + controlPlanePort() + path))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> "unexpected HTTP " + response.statusCode() + ": " + response.body());
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
    }

    private static int controlPlanePort() {
        return ((WebServerApplicationContext) controlPlane).getWebServer().getPort();
    }

    private static int gatewayPort() {
        return gateway.getBean(AgentGatewayGrpcServer.class).port();
    }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(controlPlane.getBean(DataSource.class));
    }

    private static String taskPhase(UUID taskId) {
        return jdbc().queryForObject("SELECT phase FROM tasks WHERE id = ?", String.class, taskId);
    }

    private static void awaitAgentPhase(UUID agentId, String expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            String phase = jdbc().queryForObject("SELECT phase FROM agents WHERE id = ?", String.class, agentId);
            if (expected.equals(phase)) {
                return;
            }
            Thread.yield();
        }
        assertTrue(false, "agent did not reach " + expected);
    }

    private static int countRows(String table, UUID id) {
        String column = switch (table) {
            case "task_attempts", "task_assignments" -> "task_id";
            case "agent_leases" -> "id";
            default -> throw new IllegalArgumentException("unsupported table: " + table);
        };
        Integer count = jdbc().queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, id);
        return count == null ? 0 : count;
    }

    private static void awaitTaskPhase(UUID taskId, String expected) {
        awaitTaskPhase(taskId, expected, null);
    }

    private static void awaitTaskPhase(UUID taskId, String expected, FakeAgent agent) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(taskPhase(taskId))) {
                return;
            }
            Thread.yield();
        }
        String failure = agent == null ? "none" : agent.failureDescription();
        assertTrue(false, "task did not reach " + expected + ": " + databaseSnapshot(taskId)
                + ", agentStream=" + failure);
    }

    private static String databaseSnapshot(UUID taskId) {
        String task = jdbc().queryForObject("SELECT phase || ':v' || version FROM tasks WHERE id = ?", String.class,
                taskId);
        String agents = jdbc().queryForList("SELECT phase || ':v' || version FROM agents ORDER BY id", String.class)
                .toString();
        String outbox = jdbc().queryForList(
                "SELECT event_type || ':' || status || ':attempts=' || attempts FROM outbox_events ORDER BY created_at",
                String.class).toString();
        String gateway = jdbc().queryForList(
                "SELECT sequence || ':' || event_id FROM gateway_commands ORDER BY sequence", String.class).toString();
        return "task=" + task + ", agents=" + agents + ", outbox=" + outbox + ", gateway=" + gateway;
    }

    private static int countArtifacts(UUID taskId) {
        Integer count = jdbc().queryForObject("SELECT count(*) FROM artifacts WHERE task_id = ?", Integer.class, taskId);
        return count == null ? 0 : count;
    }

    private static String artifactStorageKey(UUID taskId) {
        return jdbc().queryForObject("SELECT storage_key FROM artifacts WHERE task_id = ?", String.class, taskId);
    }

    private static long artifactSize(UUID taskId) {
        Long size = jdbc().queryForObject("SELECT size_bytes FROM artifacts WHERE task_id = ?", Long.class, taskId);
        return size == null ? -1 : size;
    }

    private static String artifactSha256(UUID taskId) {
        return jdbc().queryForObject("SELECT sha256 FROM artifacts WHERE task_id = ?", String.class, taskId);
    }

    private static String artifactStatus(UUID taskId) {
        return jdbc().queryForObject("SELECT status FROM artifacts WHERE task_id = ?", String.class, taskId);
    }

    private static String artifactAttemptId(UUID taskId) {
        return jdbc().queryForObject("SELECT attempt_id::text FROM artifacts WHERE task_id = ?", String.class, taskId);
    }

    private static int countDomainEvents(String eventId) {
        Integer count = jdbc().queryForObject("SELECT count(*) FROM domain_events WHERE event_id = ?", Integer.class,
                UUID.fromString(eventId));
        return count == null ? 0 : count;
    }

    private static int gatewayCommandCount(UUID agentId) {
        Integer count = jdbc().queryForObject("SELECT count(*) FROM gateway_commands WHERE agent_id = ?", Integer.class,
                agentId.toString());
        return count == null ? 0 : count;
    }

    private static int gatewayDeliveryCount(UUID agentId, long sequence) {
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM gateway_command_deliveries WHERE agent_id = ? AND sequence = ?",
                Integer.class, agentId.toString(), sequence);
        return count == null ? 0 : count;
    }

    private static long acknowledgedSequence(UUID agentId) {
        return jdbc().query("SELECT last_ack_sequence FROM gateway_ack_cursors WHERE agent_id = ?",
                (rs, row) -> rs.getLong(1), agentId.toString()).stream().findFirst().orElse(0L);
    }

    private static boolean configConsumerDelivered(String durable) {
        try {
            io.nats.client.api.ConsumerInfo info = natsConnection.jetStreamManagement()
                    .getConsumerInfo("AGENT_EVENTS", durable);
            return info.getDelivered() != null && info.getDelivered().getStreamSequence() > 0
                    && info.getNumAckPending() == 0 && info.getNumPending() == 0;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static void awaitTrue(BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.yield();
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    private static void uploadArtifact(String objectKey, byte[] content) throws Exception {
        MinioClient client = minioClient();
        client.putObject(PutObjectArgs.builder().bucket("agentteams").object(objectKey)
                .stream(new ByteArrayInputStream(content), content.length, -1)
                .contentType("application/octet-stream")
                .build());
    }

    private static String downloadArtifact(String objectKey) throws Exception {
        try (InputStream stream = minioClient().getObject(GetObjectArgs.builder()
                .bucket("agentteams").object(objectKey).build())) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static MinioClient minioClient() {
        return MinioClient.builder().endpoint(minioEndpoint())
                .credentials(STORAGE_ACCESS_KEY, STORAGE_SECRET_KEY).build();
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static String[] controlPlaneProperties() {
        return new String[] {
                "spring.main.web-application-type=servlet",
                "spring.main.banner-mode=off",
                "server.port=0",
                "server.address=127.0.0.1",
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + DATABASE_USER,
                "spring.datasource.password=" + DATABASE_PASSWORD,
                "agentteams.scheduler.enabled=true",
                "agentteams.scheduler.poll-interval-ms=100",
                "agentteams.nats.enabled=true",
                "agentteams.nats.url=" + natsUrl(),
                "agentteams.outbox.relay.enabled=true",
                "agentteams.outbox.relay.poll-interval-ms=100",
                "agentteams.storage.enabled=true",
                "agentteams.storage.endpoint=" + minioEndpoint(),
                "agentteams.storage.bucket=agentteams",
                "agentteams.storage.access-key=" + STORAGE_ACCESS_KEY,
                "agentteams.storage.secret-key=" + STORAGE_SECRET_KEY
        };
    }

    private static String[] gatewayProperties(String instanceId) {
        return new String[] {
                "spring.main.web-application-type=none",
                "spring.main.banner-mode=off",
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + DATABASE_USER,
                "spring.datasource.password=" + DATABASE_PASSWORD,
                "spring.flyway.enabled=false",
                "management.endpoint.health.group.readiness.include=readinessState",
                "management.endpoint.health.validate-group-membership=false",
                "agentteams.gateway.grpc.port=0",
                "agentteams.gateway.nats.enabled=true",
                "agentteams.gateway.nats.url=" + natsUrl(),
                "agentteams.gateway.nats.subject=task.events.*",
                "agentteams.gateway.nats.durable=agent-gateway-infrastructure-it",
                "agentteams.gateway.nats.instance-id=" + instanceId
        };
    }

    private static String[] commandLineProperties(String[] properties) {
        return java.util.Arrays.stream(properties).map(property -> "--" + property).toArray(String[]::new);
    }

    private static void createStreams(JetStreamManagement management) throws Exception {
        deleteIfPresent(management, "TASK_EVENTS");
        deleteIfPresent(management, "AGENT_EVENTS");
        management.addStream(StreamConfiguration.builder()
                .name("TASK_EVENTS")
                .subjects("task.events.*")
                .storageType(StorageType.Memory)
                .build());
        management.addStream(StreamConfiguration.builder()
                .name("AGENT_EVENTS")
                .subjects("agent.events.*")
                .storageType(StorageType.Memory)
                .build());
    }

    private static void deleteIfPresent(JetStreamManagement management, String stream) {
        try {
            management.deleteStream(stream);
        } catch (Exception ignored) {
            // The stream is absent on the first test invocation.
        }
    }

    private static void createBucket() throws Exception {
        MinioClient client = MinioClient.builder()
                .endpoint(minioEndpoint())
                .credentials(STORAGE_ACCESS_KEY, STORAGE_SECRET_KEY)
                .build();
        client.makeBucket(MakeBucketArgs.builder().bucket("agentteams").build());
    }

    private static String natsUrl() {
        return "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222);
    }

    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }
}
