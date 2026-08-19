package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.ArtifactRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.domain.agent.AgentPhase;
import io.agentteams.domain.task.AppliedTransition;
import io.agentteams.domain.task.DuplicateTransition;
import io.agentteams.domain.task.TaskPhase;
import io.agentteams.domain.task.TaskTransitionCommand;
import io.agentteams.domain.task.TaskTransitionResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ExecutionEventServiceTest {

    private static final Instant START = Instant.parse("2026-08-16T00:00:00Z");
    private static final Duration LEASE_DURATION = Duration.ofSeconds(60);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private FoundationPersistenceService persistence;

    @BeforeEach
    void resetDatabase() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        persistence = new FoundationPersistenceService(dataSource);
    }

    @Test
    void duplicateCompletionDoesNotCreateAnotherAttemptOrArtifact() {
        UUID taskId = UUID.randomUUID();
        AgentRecord agent = AgentRecord.create(UUID.randomUUID(), "completion-agent", AgentPhase.READY,
                "fake", "{\"python\":true}", START);
        TaskRecord queued = new TaskRecord(taskId, "complete-once", "completion test", TaskPhase.QUEUED, 0,
                "{\"requiredCapabilities\":[\"python\"]}", "test", "test", null, null,
                START, START, 0);
        persistence.inTransaction(tx -> {
            tx.agents().insert(agent);
            tx.tasks().insert(queued);
            return null;
        });

        TaskAssignmentService.AssignmentResult assignment =
                new TaskAssignmentService(persistence, LEASE_DURATION).queueReadyTask(taskId, START);
        ExecutionEventService service = new ExecutionEventService(persistence);

        TaskTransitionResult accepted = service.apply(taskId, TaskTransitionCommand.forAttempt(
                UUID.randomUUID(), assignment.task().version(), TaskPhase.ACCEPTED,
                assignment.attempt().id(), assignment.lease().id(), START.plusSeconds(1), "agent", "fake"),
                List.of());
        TaskTransitionResult running = service.apply(taskId, TaskTransitionCommand.forAttempt(
                UUID.randomUUID(), accepted.task().version(), TaskPhase.RUNNING,
                assignment.attempt().id(), assignment.lease().id(), START.plusSeconds(2), "agent", "fake"),
                List.of());

        UUID completionEventId = UUID.randomUUID();
        Instant completedAt = START.plusSeconds(3);
        TaskTransitionCommand completion = TaskTransitionCommand.forAttempt(
                completionEventId, running.task().version(), TaskPhase.SUCCEEDED,
                assignment.attempt().id(), assignment.lease().id(), completedAt, "agent", "fake");
        ArtifactRecord artifact = new ArtifactRecord(UUID.randomUUID(), taskId, assignment.attempt().id(),
                "result.txt", "tasks/" + taskId + "/result.txt", "text/plain", 7,
                "sha256-result", "AVAILABLE", "{}", completedAt, completedAt, 0);

        TaskTransitionResult firstCompletion = service.apply(taskId, completion, List.of(artifact));
        TaskTransitionResult duplicateCompletion = service.apply(taskId, completion, List.of(artifact));

        assertThat(firstCompletion).isInstanceOf(AppliedTransition.class);
        assertThat(firstCompletion.task().phase()).isEqualTo(TaskPhase.SUCCEEDED);
        assertThat(duplicateCompletion).isInstanceOf(DuplicateTransition.class);
        assertThat(duplicateCompletion.task().phase()).isEqualTo(TaskPhase.SUCCEEDED);
        assertThat(persistence.findTask(taskId)).get().extracting(TaskRecord::phase)
                .isEqualTo(TaskPhase.SUCCEEDED);
        long attemptCount = persistence.inTransaction(tx -> tx.taskAttempts().count());
        var completedAttempt = persistence.inTransaction(tx -> tx.taskAttempts()
                .findById(assignment.attempt().id()));
        var persistedArtifact = persistence.inTransaction(tx -> tx.artifacts().findById(artifact.id()));
        long artifactCount = persistence.inTransaction(tx -> tx.artifacts()
                .countByAttemptId(assignment.attempt().id()));

        assertThat(attemptCount).isEqualTo(1);
        assertThat(completedAttempt).get()
                .satisfies(attempt -> {
                    assertThat(attempt.phase()).isEqualTo(TaskPhase.SUCCEEDED);
                    assertThat(attempt.completedAt()).isEqualTo(completedAt);
                });
        var completedLease = persistence.inTransaction(tx -> tx.agentLeases().findById(assignment.lease().id()));
        assertThat(completedLease).get().satisfies(lease -> {
            assertThat(lease.status()).isEqualTo("RELEASED");
            assertThat(lease.releasedAt()).isEqualTo(completedAt);
        });
        assertThat(persistedArtifact).contains(artifact);
        assertThat(artifactCount).isEqualTo(1);
    }
}
