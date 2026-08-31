package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TeamMemberRecord;
import io.agentteams.controlplane.persistence.TeamPolicyRecord;
import io.agentteams.controlplane.persistence.TeamRecord;
import io.agentteams.controlplane.persistence.TaskAssignmentRecord;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.domain.agent.AgentPhase;
import io.agentteams.domain.task.TaskPhase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class TeamTaskAssignmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final UUID TEAM = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID AGENT = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID MEMBERSHIP = UUID.fromString("30000000-0000-0000-0000-000000000001");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private FoundationPersistenceService persistence;

    @BeforeEach
    void resetDatabase() {
        Flyway.configure().locations("filesystem:src/main/resources/db/migration").dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false).load().clean();
        Flyway.configure().locations("filesystem:src/main/resources/db/migration").dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        persistence = new FoundationPersistenceService(dataSource);
    }

    @Test
    void keepsTeamTaskQueuedWhenConcurrencyLimitIsReached() {
        UUID busyTaskId = UUID.randomUUID();
        UUID queuedTaskId = UUID.randomUUID();
        setupTeam(1, false, List.of(), List.of(), "qwenpaw", true);
        TaskRecord busyTask = task(busyTaskId, TaskPhase.ASSIGNED, "{}");
        TaskRecord queuedTask = task(queuedTaskId, TaskPhase.QUEUED,
                "{\"teamId\":\"" + TEAM + "\",\"requiredCapabilities\":[\"python\"]}");
        persistence.inTransaction(tx -> {
            tx.tasks().insert(busyTask);
            tx.tasks().insert(queuedTask);
            tx.teams().linkTask(TEAM, busyTaskId, "NOT_REQUIRED", NOW);
            tx.teams().insertTaskAssignment(UUID.randomUUID(), TEAM, busyTaskId, AGENT, MEMBERSHIP,
                    "ASSIGNED", NOW);
            return null;
        });

        TaskAssignmentService service = new TaskAssignmentService(persistence, Duration.ofSeconds(30));

        assertThatThrownBy(() -> service.queueReadyTask(queuedTaskId, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no READY agent");
        assertThat(persistence.findTask(queuedTaskId)).get().extracting(TaskRecord::phase)
                .isEqualTo(TaskPhase.QUEUED);
    }

    @Test
    void rejectsTeamTaskWhenRuntimeOrApprovalPolicyIsNotSatisfied() {
        UUID taskId = UUID.randomUUID();
        setupTeam(1, true, List.of("qwenpaw"), List.of("python"), "other-runtime", false);
        persistence.inTransaction(tx -> {
            tx.tasks().insert(task(taskId, TaskPhase.QUEUED,
                    "{\"teamId\":\"" + TEAM + "\",\"requiredCapabilities\":[\"python\"],"
                            + "\"approvalGranted\":false}"));
            return null;
        });

        TaskAssignmentService service = new TaskAssignmentService(persistence, Duration.ofSeconds(30));

        assertThatThrownBy(() -> service.queueReadyTask(taskId, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no READY agent");
        assertThat(persistence.findTask(taskId)).get().extracting(TaskRecord::phase)
                .isEqualTo(TaskPhase.QUEUED);
    }

    @Test
    void assignsEligibleTeamMemberAndRecordsTeamAssignment() {
        UUID taskId = UUID.randomUUID();
        setupTeam(1, false, List.of("qwenpaw"), List.of("python"), "qwenpaw", true);
        persistence.inTransaction(tx -> {
            tx.tasks().insert(task(taskId, TaskPhase.QUEUED,
                    "{\"teamId\":\"" + TEAM + "\",\"requiredCapabilities\":[\"python\"]}"));
            return null;
        });

        TaskAssignmentService.AssignmentResult result = new TaskAssignmentService(
                persistence, Duration.ofSeconds(30)).queueReadyTask(taskId, NOW);

        assertThat(result.agent().id()).isEqualTo(AGENT);
        assertThat((Integer) persistence.inTransaction(tx -> tx.teams().activeAssignmentCount(TEAM))).isEqualTo(1);
    }

    private void setupTeam(int maxConcurrent, boolean requireApproval, List<String> allowedRuntimes,
            List<String> requiredCapabilities, String runtime, boolean approvalGranted) {
        persistence.inTransaction(tx -> {
            tx.agents().insert(AgentRecord.create(AGENT, "team-agent", AgentPhase.READY, runtime,
                    "{\"python\":true}", NOW));
            tx.teams().insert(TeamRecord.create(TEAM, "agentteams/team", "team", NOW));
            tx.teams().insertPolicy(new TeamPolicyRecord(TEAM, maxConcurrent, requireApproval,
                    allowedRuntimes, requiredCapabilities, NOW, 0));
            tx.teams().insertMember(new TeamMemberRecord(MEMBERSHIP, TEAM, AGENT, "worker", "ACTIVE",
                    NOW, NOW, 0));
            return null;
        });
    }

    private static TaskRecord task(UUID id, TaskPhase phase, String spec) {
        return new TaskRecord(id, "team-task", "team task", phase, 10, spec,
                "test", "test", null, null, NOW, NOW, 0);
    }
}
