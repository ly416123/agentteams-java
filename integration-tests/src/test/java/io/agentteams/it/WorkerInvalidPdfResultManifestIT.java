package io.agentteams.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentteams.application.api.ExecutionEventPort.ArtifactReference;
import io.agentteams.application.api.ExecutionEventPort.ExecutionPhase;
import io.agentteams.application.api.ExecutionEventPort.TaskExecutionCommand;
import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.ControlPlaneApplication;
import io.agentteams.controlplane.application.ControlPlaneExecutionEventAdapter;
import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.CreateTaskCommand;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.service.TaskAssignmentService;
import io.agentteams.controlplane.task.JdbcTaskRunObservationRepository;
import io.agentteams.controlplane.task.TaskResultManifestService;
import io.agentteams.domain.agent.AgentPhase;
import io.agentteams.domain.task.TaskPhase;
import io.agentteams.runtime.AgentChannelPort;
import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.GatewayRuntimeAdapter;
import io.agentteams.runtime.RuntimeResult;
import io.agentteams.worker.agentscope.AgentScopeRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

/** Full fake Worker runtime to Control Plane Result Manifest path for invalid PDF bytes. */
@Testcontainers(disabledWithoutDocker = true)
class WorkerInvalidPdfResultManifestIT {

    private static final Instant START = Instant.parse("2026-11-02T00:00:00Z");
    private static final String DATABASE_USER = "agentteams";
    private static final String DATABASE_PASSWORD = "agentteams-dev";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("agentteams")
            .withUsername(DATABASE_USER)
            .withPassword(DATABASE_PASSWORD)
            .withTmpFs(Map.of("/var/lib/postgresql/data", "rw"));

    private static ConfigurableApplicationContext controlPlane;

    @BeforeAll
    static void startControlPlane() {
        controlPlane = new SpringApplicationBuilder(ControlPlaneApplication.class)
                .run("--spring.main.web-application-type=none", "--spring.main.banner-mode=off",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + DATABASE_USER,
                        "--spring.datasource.password=" + DATABASE_PASSWORD,
                        "--agentteams.scheduler.enabled=false", "--agentteams.team-sync.enabled=false",
                        "--agentteams.nats.enabled=false", "--agentteams.storage.enabled=false");
    }

    @AfterAll
    static void stopControlPlane() {
        if (controlPlane != null) controlPlane.close();
    }

    @ParameterizedTest(name = "worker publishes rejected PDF fixture {0}")
    @MethodSource("invalidPdfFixtures")
    void successfulWorkerPublishesInvalidPdfMetadataToJdbcManifest(InvalidPdfFixture fixture, @TempDir Path tempDir)
            throws Exception {
        assertThat(hexSha256(fixture.bytes())).isEqualTo(fixture.expectedSha256());
        FoundationPersistenceService persistence = controlPlane.getBean(FoundationPersistenceService.class);
        TaskAssignmentService assignments = controlPlane.getBean(TaskAssignmentService.class);
        ControlPlaneExecutionEventAdapter events = controlPlane.getBean(ControlPlaneExecutionEventAdapter.class);
        TaskResultManifestService manifests = controlPlane.getBean(TaskResultManifestService.class);
        JdbcTaskRunObservationRepository runs = controlPlane.getBean(JdbcTaskRunObservationRepository.class);

        UUID agentId = UUID.randomUUID();
        String requiredCapability = "invalid_pdf_" + fixture.name().replace('-', '_');
        persistence.inTransaction(tx -> {
            tx.agents().insert(AgentRecord.create(agentId, "invalid-pdf-worker-" + agentId,
                    AgentPhase.READY, "agentscope", "{\"" + requiredCapability + "\":true}", START));
            tx.insertResourceScope("WORKER", agentId, "tenant-invalid-pdf", "project-invalid-pdf",
                    "team-invalid-pdf", START);
            return null;
        });
        TaskRecord draft = persistence.createTask(new CreateTaskCommand("invalid-pdf-" + UUID.randomUUID(),
                "Invalid PDF integration", "Worker result manifest integration", "test", "integration-test",
                "{\"taskType\":\"NORMAL\",\"requiredCapabilities\":[\"" + requiredCapability + "\"]}", START));
        UUID taskId = draft.id();
        persistence.inTransaction(tx -> {
            tx.insertResourceScope("TASK", taskId, "tenant-invalid-pdf", "project-invalid-pdf",
                    "team-invalid-pdf", START);
            return null;
        });
        TaskRecord queued = persistence.updateTaskPhase(draft.id(), TaskPhase.QUEUED, draft.version(), START);
        assertThat(queued.id()).isEqualTo(taskId);

        TaskAssignmentService.AssignmentResult assignment = assignments.queueReadyTask(taskId, START);
        UUID assignedAgentId = assignment.agent().id();
        assertThat(assignedAgentId).isEqualTo(agentId);
        ExecutionContext context = runs.contextForTask(taskId).orElseThrow();
        Instant acceptedAt = START.plusSeconds(1);
        TaskExecutionCommand accepted = command(UUID.randomUUID(), assignment.task().version(), assignment,
                assignedAgentId, acceptedAt, ExecutionPhase.ACCEPTED, "");
        events.apply(taskId, accepted, List.of());
        TaskRecord acceptedTask = persistence.findTask(taskId).orElseThrow();
        TaskExecutionCommand running = command(UUID.randomUUID(), acceptedTask.version(), assignment,
                assignedAgentId, START.plusSeconds(2), ExecutionPhase.RUNNING, "");
        events.apply(taskId, running, List.of());

        CountDownLatch workerFinished = new CountDownLatch(1);
        RuntimeResult[] workerResult = new RuntimeResult[1];
        AgentScopeRuntime runtime = new AgentScopeRuntime((task, agentContext) ->
                makeAgent(tempDir.resolve(UUID.randomUUID().toString())));
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        runtime.start(new AgentRuntimeContext("AGENTSCOPE", 1, clock, result -> {
            workerResult[0] = result;
            try {
                TaskRecord runningTask = persistence.findTask(taskId).orElseThrow();
                Instant completedAt = START.plusSeconds(3);
                TaskExecutionCommand succeeded = command(UUID.randomUUID(), runningTask.version(), assignment,
                        assignedAgentId, completedAt, ExecutionPhase.SUCCEEDED, result.output());
                events.apply(taskId, succeeded, List.of(artifact(taskId, assignment.attempt().id(), fixture.bytes())));
            } finally {
                workerFinished.countDown();
            }
        }, Map.of("provider_id", "fake-agentscope", "model", "fake-model")));
        try {
            GatewayRuntimeAdapter gateway = new GatewayRuntimeAdapter("invalid-pdf-it", (AgentChannelPort) message -> {},
                    runtime, clock);
            assertThat(gateway.acceptAssignment(taskAssigned(taskId, assignedAgentId, assignment)).accepted()).isTrue();
            assertThat(workerFinished.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(workerResult[0]).isNotNull();
            assertThat(workerResult[0].success()).isTrue();

            TaskResultManifest manifest = manifests.get(context, taskId, assignment.attempt().id(),
                    Set.of(TaskEventVisibility.REQUESTER)).orElseThrow();
            assertThat(manifest.status()).isEqualTo("SUCCEEDED");
            assertThat(manifest.taskId()).isEqualTo(taskId);
            assertThat(manifest.runId()).isEqualTo(assignment.attempt().id());
            assertThat(manifest.artifacts()).singleElement().satisfies(saved -> {
                assertThat(saved.name()).isEqualTo("invalid.pdf");
                assertThat(saved.storageRef()).isEqualTo(storageKey(taskId, assignment.attempt().id()));
                assertThat(saved.contentType()).isEqualTo("application/pdf");
                assertThat(saved.sizeBytes()).isEqualTo(fixture.bytes().length);
                assertThat(saved.sha256()).isEqualTo(hexSha256(fixture.bytes()));
                assertThat(saved.stage()).isEqualTo("FINAL");
                assertThat(saved.visibility()).isEqualTo(TaskEventVisibility.REQUESTER);
            });
            assertThat(persistence.findTask(taskId)).get().extracting(TaskRecord::phase).isEqualTo(TaskPhase.SUCCEEDED);
        } finally {
            runtime.stop();
        }
    }

    private static TaskExecutionCommand command(UUID eventId, long version,
            TaskAssignmentService.AssignmentResult assignment, UUID agentId, Instant at,
            ExecutionPhase phase, String result) {
        return new TaskExecutionCommand(eventId, version, assignment.attempt().id(), assignment.lease().id(), at,
                agentId.toString(), "integration-test", phase, null, null, "invalid-pdf-it", "", "", null, result);
    }

    private static ArtifactReference artifact(UUID taskId, UUID attemptId, byte[] bytes) {
        return new ArtifactReference("invalid.pdf", storageKey(taskId, attemptId), "application/pdf",
                bytes.length, hexSha256(bytes), "{\"fixture\":\"invalid-pdf-result-manifest\"}");
    }

    private static String storageKey(UUID taskId, UUID attemptId) {
        return "tasks/" + taskId + "/attempts/" + attemptId + "/artifacts/invalid.pdf";
    }

    private static String hexSha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static io.agentteams.contracts.v1.TaskAssigned taskAssigned(UUID taskId, UUID agentId,
            TaskAssignmentService.AssignmentResult assignment) {
        return io.agentteams.contracts.v1.TaskAssigned.newBuilder()
                .setMetadata(io.agentteams.contracts.v1.EventMetadata.newBuilder()
                        .setEventId(UUID.randomUUID().toString()).setAgentId(agentId.toString())
                        .setTaskId(taskId.toString()).setAttemptId(assignment.attempt().id().toString())
                        .setLeaseId(assignment.lease().id().toString())
                        .setExpectedVersion(assignment.task().version()).build())
                .setTaskType("NORMAL")
                .setInputJson(com.google.protobuf.ByteString.copyFromUtf8("{\"input\":\"invalid PDF fixture\"}"))
                .setLeaseExpiresAt(com.google.protobuf.util.Timestamps.fromMillis(
                        assignment.lease().expiresAt().toEpochMilli()))
                .build();
    }

    private static HarnessAgent makeAgent(Path workspace) {
        try {
            Files.createDirectories(workspace);
            return HarnessAgent.builder().name("invalid-pdf-worker").agentId("invalid-pdf-it")
                    .defaultSessionId(UUID.randomUUID().toString()).model(new FixedModel()).workspace(workspace)
                    .disableFilesystemTools().disableShellTool().disableSubagents().disableSessionPersistence()
                    .maxIters(2).build();
        } catch (Exception error) {
            throw new IllegalStateException("unable to create fake AgentScope worker", error);
        }
    }

    private static final class FixedModel implements Model {
        @Override
        public Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages,
                List<ToolSchema> tools, GenerateOptions options) {
            TextBlock answer = TextBlock.builder().text("Worker completed invalid PDF fixture").build();
            return Flux.just(ChatResponse.builder().id("invalid-pdf-response").content(List.of(answer))
                    .usage(new ChatUsage(1, 1, 0)).metadata(Map.of()).finishReason("stop").build());
        }

        @Override
        public String getModelName() {
            return "fake-model";
        }
    }

    private static java.util.stream.Stream<InvalidPdfFixture> invalidPdfFixtures() {
        // Shared invalid PDF fixtures and pinned hashes live in scripts/test_agentteams_eval.py.
        return java.util.stream.Stream.of(
                new InvalidPdfFixture("missing-signature", "plain text pretending to be a PDF\n"
                        .getBytes(StandardCharsets.UTF_8),
                        "6c4e5bc8f645cc2d110583431037db447bfd107d0f8101385d0b1b44c018f4c9"),
                new InvalidPdfFixture("invalid-page-tree", "%PDF-1.7\nthis file has a PDF header but no valid page tree\n%%EOF\n"
                        .getBytes(StandardCharsets.UTF_8),
                        "2eea3ddd60ec0f0cf76b4d7bd58b8cd872ba03ee5d5f4513878edc1f46de00bb"));
    }

    record InvalidPdfFixture(String name, byte[] bytes, String expectedSha256) {
        @Override
        public String toString() {
            return name;
        }
    }
}
