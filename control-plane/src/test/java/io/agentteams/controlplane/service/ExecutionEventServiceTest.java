package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.ArtifactRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.AuthorizationException;
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
    private org.postgresql.ds.PGSimpleDataSource dataSource;

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

        dataSource = new org.postgresql.ds.PGSimpleDataSource();
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

        assertThatThrownBy(() -> service.apply(taskId, TaskTransitionCommand.forAttempt(
                UUID.randomUUID(), assignment.task().version(), TaskPhase.ACCEPTED,
                assignment.attempt().id(), assignment.lease().id(), START.plusSeconds(1),
                UUID.randomUUID().toString(), "fake"), List.of()))
                .isInstanceOf(AuthorizationException.class);

        TaskTransitionResult accepted = service.apply(taskId, TaskTransitionCommand.forAttempt(
                UUID.randomUUID(), assignment.task().version(), TaskPhase.ACCEPTED,
                assignment.attempt().id(), assignment.lease().id(), START.plusSeconds(1),
                agent.id().toString(), "fake"),
                List.of());
        TaskTransitionResult running = service.apply(taskId, TaskTransitionCommand.forAttempt(
                UUID.randomUUID(), accepted.task().version(), TaskPhase.RUNNING,
                assignment.attempt().id(), assignment.lease().id(), START.plusSeconds(2),
                agent.id().toString(), "fake"),
                List.of());

        UUID completionEventId = UUID.randomUUID();
        Instant completedAt = START.plusSeconds(3);
        TaskTransitionCommand completion = TaskTransitionCommand.forAttempt(
                completionEventId, running.task().version(), TaskPhase.SUCCEEDED,
                assignment.attempt().id(), assignment.lease().id(), completedAt, agent.id().toString(), "fake");
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

    @Test
    void rejectsUnacceptedAssignmentByReclaimingLeaseAndQueuingTask() throws Exception {
        UUID taskId = UUID.randomUUID();
        AgentRecord agent = AgentRecord.create(UUID.randomUUID(), "reject-agent", AgentPhase.READY,
                "fake", "{\"python\":true}", START);
        TaskRecord queued = new TaskRecord(taskId, "reject-once", "rejection test", TaskPhase.QUEUED, 0,
                "{\"requiredCapabilities\":[\"python\"]}", "test", "test", null, null,
                START, START, 0);
        persistence.inTransaction(tx -> {
            tx.agents().insert(agent);
            tx.tasks().insert(queued);
            return null;
        });

        TaskAssignmentService.AssignmentResult assignment =
                new TaskAssignmentService(persistence, LEASE_DURATION).queueReadyTask(taskId, START);

        // 模拟 Gateway 已持久化的分配命令(回收时应把该命令作废)
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        INSERT INTO gateway_commands
                            (agent_id, sequence, event_id, attempt_id, command_bytes, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
            statement.setString(1, agent.id().toString());
            statement.setLong(2, 1);
            statement.setString(3, assignment.eventId().toString());
            statement.setString(4, assignment.attempt().id().toString());
            statement.setBytes(5, new byte[]{1});
            statement.setTimestamp(6, java.sql.Timestamp.from(START));
            statement.executeUpdate();
        }

        ExecutionEventService service = new ExecutionEventService(persistence);
        Instant rejectionAt = START.plusSeconds(1);
        service.rejectUnaccepted(taskId, new io.agentteams.domain.task.RejectionCommand(
                UUID.randomUUID(), assignment.task().version(), assignment.attempt().id(),
                assignment.lease().id(), rejectionAt, agent.id().toString(), "gateway",
                "already running"));

        assertThat(persistence.findTask(taskId)).get().extracting(TaskRecord::phase)
                .isEqualTo(TaskPhase.QUEUED);
        var reclaimedLease = persistence.inTransaction(tx -> tx.agentLeases().findById(assignment.lease().id()));
        assertThat(reclaimedLease).get().satisfies(lease -> {
            assertThat(lease.status()).isEqualTo("EXPIRED");
            assertThat(lease.releasedAt()).isEqualTo(rejectionAt);
        });
        var reclaimedAttempt = persistence.inTransaction(tx -> tx.taskAttempts()
                .findById(assignment.attempt().id()));
        assertThat(reclaimedAttempt).get().satisfies(attempt ->
                assertThat(attempt.phase()).isEqualTo(TaskPhase.CANCELLED));
        long pendingCommands = persistence.inTransaction(tx ->
                tx.pendingGatewayCommandCount(assignment.attempt().id()));
        assertThat(pendingCommands).isZero();

        TaskAssignmentService.AssignmentResult redelivered =
                new TaskAssignmentService(persistence, LEASE_DURATION).queueReadyTask(taskId, rejectionAt);
        assertThat(redelivered.attempt().id()).isNotEqualTo(assignment.attempt().id());
        assertThat(redelivered.lease().id()).isNotEqualTo(assignment.lease().id());
        assertThat(redelivered.task().phase()).isEqualTo(TaskPhase.ASSIGNED);
    }

    @Test
    void rejectsUnacceptedAssignmentIdempotentlyWhenLeaseIsAlreadyReclaimed() {
        UUID taskId = UUID.randomUUID();
        AgentRecord agent = AgentRecord.create(UUID.randomUUID(), "reject-idempotent", AgentPhase.READY,
                "fake", "{\"python\":true}", START);
        TaskRecord queued = new TaskRecord(taskId, "reject-twice", "idempotency test", TaskPhase.QUEUED, 0,
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
        io.agentteams.domain.task.RejectionCommand command =
                new io.agentteams.domain.task.RejectionCommand(
                        UUID.randomUUID(), assignment.task().version(), assignment.attempt().id(),
                        assignment.lease().id(), START.plusSeconds(1), agent.id().toString(), "gateway",
                        "busy");

        service.rejectUnaccepted(taskId, command);
        service.rejectUnaccepted(taskId, command);

        assertThat(persistence.findTask(taskId)).get().extracting(TaskRecord::phase)
                .isEqualTo(TaskPhase.QUEUED);
        long attempts = persistence.inTransaction(tx -> tx.taskAttempts().count());
        assertThat(attempts).isEqualTo(1);
        // 事件只记录一次(幂等)
        long eventCount = persistence.inTransaction(tx -> tx.domainEvents().count());
        assertThat(eventCount).isEqualTo(2);
    }
}
