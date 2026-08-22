package io.agentteams.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentteams.controlplane.ControlPlaneApplication;
import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.CreateTaskCommand;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.service.TaskAssignmentService;
import io.agentteams.controlplane.team.TeamCrdParser;
import io.agentteams.controlplane.team.TeamCrdSynchronizer;
import io.agentteams.domain.agent.AgentPhase;
import io.agentteams.domain.task.TaskPhase;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Acceptance test for the real Spring Control Plane Team scheduling path. */
@Testcontainers(disabledWithoutDocker = true)
class TeamSchedulingInfrastructureIT {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final String DATABASE_USER = "agentteams";
    private static final String DATABASE_PASSWORD = "agentteams-dev";
    private static final UUID LEADER = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID WORKER = UUID.fromString("50000000-0000-0000-0000-000000000002");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("agentteams")
            .withUsername(DATABASE_USER)
            .withPassword(DATABASE_PASSWORD);

    private static ConfigurableApplicationContext controlPlane;

    @BeforeAll
    static void startControlPlane() {
        controlPlane = new SpringApplicationBuilder(ControlPlaneApplication.class)
                .run(commandLineProperties());
    }

    @AfterAll
    static void stopControlPlane() {
        if (controlPlane != null) {
            controlPlane.close();
        }
    }

    @Test
    void synchronizesTeamCrdAndAppliesConcurrencyPolicyThroughRealControlPlaneBeans() {
        FoundationPersistenceService persistence = controlPlane.getBean(FoundationPersistenceService.class);
        TeamCrdSynchronizer synchronizer = controlPlane.getBean(TeamCrdSynchronizer.class);
        TaskAssignmentService assignments = controlPlane.getBean(TaskAssignmentService.class);
        persistence.inTransaction(tx -> {
            tx.agents().insert(agent(LEADER, "team-leader"));
            tx.agents().insert(agent(WORKER, "team-worker"));
            return null;
        });

        synchronizer.apply(teamResource());
        UUID firstTaskId = createQueuedTask(persistence, "first");
        UUID secondTaskId = createQueuedTask(persistence, "second");

        TaskAssignmentService.AssignmentResult first = assignments.queueReadyTask(firstTaskId, NOW);

        assertEquals(LEADER, first.agent().id());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> assignments.queueReadyTask(secondTaskId, NOW));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("no READY agent"));
        assertEquals(TaskPhase.QUEUED, persistence.findTask(secondTaskId).orElseThrow().phase());
        assertEquals(1, (int) persistence.inTransaction(tx -> tx.teams()
                .activeAssignmentCount(TeamCrdParser.stableId("agentteams", "platform"))));
    }

    private static UUID createQueuedTask(FoundationPersistenceService persistence, String suffix) {
        TaskRecord draft = persistence.createTask(new CreateTaskCommand(
                "team-it-task-" + suffix + "-" + UUID.randomUUID(), "team task " + suffix,
                "Team scheduling acceptance", "integration-test", "integration-test",
                "{\"teamId\":\"" + TeamCrdParser.stableId("agentteams", "platform")
                        + "\",\"requiredCapabilities\":[\"python\"]}", NOW));
        return persistence.updateTaskPhase(draft.id(), TaskPhase.QUEUED, draft.version(), NOW).id();
    }

    private static AgentRecord agent(UUID id, String name) {
        return AgentRecord.create(id, name, AgentPhase.READY, "qwenpaw", "{\"python\":true}", NOW);
    }

    private static GenericKubernetesResource teamResource() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("maxConcurrentTasks", 1);
        policy.put("requireApproval", false);
        policy.put("allowedRuntimes", List.of("qwenpaw"));
        policy.put("requiredCapabilities", List.of("python"));
        Map<String, Object> member = Map.of(
                "agentRef", LEADER.toString(), "role", "leader", "capabilities", List.of("python"));
        Map<String, Object> secondMember = Map.of(
                "agentRef", WORKER.toString(), "role", "worker", "capabilities", List.of("python"));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("leaderRef", LEADER.toString());
        spec.put("members", List.of(member, secondMember));
        spec.put("policy", policy);
        GenericKubernetesResource resource = new GenericKubernetesResource();
        resource.setApiVersion("agentteams.io/v1alpha1");
        resource.setKind("Team");
        resource.setMetadata(new ObjectMetaBuilder().withNamespace("agentteams").withName("platform")
                .withResourceVersion("1").build());
        resource.setAdditionalProperties(new LinkedHashMap<>(Map.of("spec", spec)));
        return resource;
    }

    private static String[] commandLineProperties() {
        return new String[] {
                "--spring.main.web-application-type=none",
                "--spring.main.banner-mode=off",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + DATABASE_USER,
                "--spring.datasource.password=" + DATABASE_PASSWORD,
                "--agentteams.scheduler.enabled=false",
                "--agentteams.team-sync.enabled=false",
                "--agentteams.nats.enabled=false",
                "--agentteams.storage.enabled=false"
        };
    }
}
