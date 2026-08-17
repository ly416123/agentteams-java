package io.agentteams.controlplane.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.domain.agent.AgentPhase;
import io.agentteams.domain.task.TaskAttempt;
import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@ExtendWith(SpringExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class FoundationRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private FoundationPersistenceService persistence;

    @BeforeEach
    void migrate() {
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
    void writesAgentTaskAttemptDomainEventAndOutboxInOneTransaction() {
        // The service is initialized with the same database in the implementation.
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID leaseId = UUID.randomUUID();

        AgentRecord agent = AgentRecord.create(agentId, "agent-1", AgentPhase.READY,
                "fake", "{}", now);
        TaskRecord task = TaskRecord.draft(taskId, "Persist this task", "description", "actor", "test", now);
        TaskAttemptRecord attempt = TaskAttemptRecord.fromDomain(new TaskAttempt(
                attemptId, taskId, leaseId, TaskPhase.ASSIGNED, now, now, now.plusSeconds(60), null,
                "scheduler", "test", null, null, 0));
        UUID assignmentId = UUID.randomUUID();
        UUID leaseIdForAssignment = leaseId;
        TaskAssignmentRecord assignment = new TaskAssignmentRecord(assignmentId, taskId, attemptId, agentId,
                TaskPhase.ASSIGNED, now, null, null, "{}", now, now, 0);
        AgentLeaseRecord lease = new AgentLeaseRecord(leaseIdForAssignment, agentId, attemptId, now,
                now.plusSeconds(60), null, "ACTIVE", now, now, 0);

        UUID eventId = persistence.createFoundation(agent, task, attempt, assignment, lease, now);

        Optional<AgentRecord> persistedAgent = persistence.inTransaction(tx -> tx.agents().findById(agentId));
        Optional<TaskRecord> persistedTask = persistence.inTransaction(tx -> tx.tasks().findById(taskId));
        Optional<TaskAttemptRecord> persistedAttempt = persistence.inTransaction(
                tx -> tx.taskAttempts().findById(attemptId));
        Optional<DomainEventRecord> persistedDomainEvent = persistence.inTransaction(
                tx -> tx.domainEvents().findByEventId(eventId));
        Optional<OutboxEventRecord> persistedOutboxEvent = persistence.inTransaction(
                tx -> tx.outboxEvents().findByEventId(eventId));
        List<String> domainEventTypes = persistence.inTransaction(tx -> tx.domainEvents().eventTypes());
        List<String> outboxEventTypes = persistence.inTransaction(tx -> tx.outboxEvents().eventTypes());

        assertThat(persistedAgent).contains(agent);
        assertThat(persistedTask).contains(task);
        assertThat(persistedAttempt).contains(attempt);
        assertThat(persistedDomainEvent).isPresent();
        assertThat(persistedOutboxEvent).isPresent();
        assertThat(domainEventTypes)
                .containsExactly("AgentCreated", "AgentLeaseCreated", "TaskAssignmentCreated",
                        "TaskAttemptCreated", "TaskCreated");
        assertThat(outboxEventTypes)
                .containsExactly("AgentCreated", "AgentLeaseCreated", "TaskAssignmentCreated",
                        "TaskAttemptCreated", "TaskCreated");

        TaskAttemptRecord sanitizedAttempt = persistence.updateAttemptPhase(attemptId, TaskPhase.FAILED,
                now.plusSeconds(1), "RUNTIME", "token=attempt-secret password:attempt-password "
                        + "https://example.test/?api_key=url-secret", 0, now.plusSeconds(1));
        assertThat(sanitizedAttempt.redactedFailureMessage())
                .contains("[REDACTED]")
                .doesNotContain("attempt-secret", "attempt-password", "url-secret");
    }

    @Test
    void rejectsTheSecondConcurrentTaskVersionUpdateWithTypedFailure() throws Exception {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        UUID taskId = UUID.randomUUID();
        TaskRecord task = TaskRecord.draft(taskId, "Concurrent task", "description", "actor", "test", now);
        persistence.inTransaction(tx -> {
            tx.tasks().insert(task);
            return null;
        });

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Callable<Object> update = () -> {
            ready.countDown();
            release.await(10, TimeUnit.SECONDS);
            return persistence.inTransaction(tx -> tx.tasks().updatePhase(
                    taskId, TaskPhase.QUEUED, 0, now.plusSeconds(1)));
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(update);
            Future<Object> second = executor.submit(update);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            Future<?>[] results = {first, second};
            long failures = 0;
            for (Future<?> result : results) {
                try {
                    result.get(10, TimeUnit.SECONDS);
                } catch (ExecutionException error) {
                    assertThat(error.getCause()).isInstanceOf(OptimisticLockFailure.class);
                    OptimisticLockFailure conflict = (OptimisticLockFailure) error.getCause();
                    assertThat(conflict.expectedVersion()).isZero();
                    assertThat(conflict.actualVersion()).isEqualTo(1);
                    failures++;
                }
            }
            assertThat(failures).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void returnsTheOriginalTaskForARepeatedIdempotencyKey() {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        CreateTaskCommand command = CreateTaskCommand.of(
                "same-key", "Idempotent task", "description", "actor", "api", now);

        TaskRecord first = persistence.createTask(command);
        TaskRecord second = persistence.createTask(command);

        assertThat(second).isEqualTo(first);
        long taskCount = persistence.inTransaction(tx -> tx.tasks().count());
        assertThat(taskCount).isEqualTo(1);
        assertThatThrownBy(() -> persistence.createTask(CreateTaskCommand.of(
                "same-key", "different task", "description", "actor", "api", now)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void rollsBackAllFoundationRowsAndEventsWhenTheTransactionFails() {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID leaseId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        AgentRecord agent = AgentRecord.create(agentId, "rollback-agent", AgentPhase.READY, "fake", "{}", now);
        TaskRecord task = TaskRecord.draft(taskId, "rollback-task", "description", "actor", "test", now);
        TaskAttemptRecord attempt = TaskAttemptRecord.fromDomain(new TaskAttempt(
                attemptId, taskId, leaseId, TaskPhase.ASSIGNED, now, now, now.plusSeconds(60), null,
                "scheduler", "test", null, null, 0));
        TaskAssignmentRecord assignment = new TaskAssignmentRecord(assignmentId, taskId, attemptId, agentId,
                TaskPhase.ASSIGNED, now, null, null, "{}", now, now, 0);
        AgentLeaseRecord lease = new AgentLeaseRecord(leaseId, agentId, attemptId, now,
                now.plusSeconds(60), null, "ACTIVE", now, now, 0);

        assertThatThrownBy(() -> persistence.inTransaction(tx -> insertAndFail(
                tx, agent, task, attempt, assignment, lease, eventId, now)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("intentional rollback");

        long agentCount = persistence.inTransaction(tx -> tx.agents().count());
        long taskCount = persistence.inTransaction(tx -> tx.tasks().count());
        long taskAttemptCount = persistence.inTransaction(tx -> tx.taskAttempts().count());
        long taskAssignmentCount = persistence.inTransaction(tx -> tx.taskAssignments().count());
        long agentLeaseCount = persistence.inTransaction(tx -> tx.agentLeases().count());
        long domainEventCount = persistence.inTransaction(tx -> tx.domainEvents().count());
        long outboxEventCount = persistence.inTransaction(tx -> tx.outboxEvents().count());

        assertThat(agentCount).isZero();
        assertThat(taskCount).isZero();
        assertThat(taskAttemptCount).isZero();
        assertThat(taskAssignmentCount).isZero();
        assertThat(agentLeaseCount).isZero();
        assertThat(domainEventCount).isZero();
        assertThat(outboxEventCount).isZero();
    }

    @Test
    void concurrentSameIdempotencyKeyCreatesOneTaskAndOneIdempotencyRecord() throws Exception {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        CreateTaskCommand command = CreateTaskCommand.of(
                "concurrent-key", "Concurrent idempotent task", "description", "actor", "api", now);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Callable<TaskRecord> create = () -> {
            ready.countDown();
            release.await(10, TimeUnit.SECONDS);
            return persistence.createTask(command);
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<TaskRecord> first = executor.submit(create);
            Future<TaskRecord> second = executor.submit(create);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            TaskRecord firstTask = first.get(10, TimeUnit.SECONDS);
            TaskRecord secondTask = second.get(10, TimeUnit.SECONDS);
            assertThat(secondTask).isEqualTo(firstTask);
        } finally {
            executor.shutdownNow();
        }

        long taskCount = persistence.inTransaction(tx -> tx.tasks().count());
        long idempotencyKeyCount = persistence.inTransaction(tx -> tx.idempotencyKeys().count());
        assertThat(taskCount).isEqualTo(1);
        assertThat(idempotencyKeyCount).isEqualTo(1);
    }

    private static Void insertAndFail(FoundationTransaction tx, AgentRecord agent, TaskRecord task,
            TaskAttemptRecord attempt, TaskAssignmentRecord assignment, AgentLeaseRecord lease,
            UUID eventId, Instant now) {
        tx.agents().insert(agent);
        tx.tasks().insert(task);
        tx.taskAttempts().insert(attempt);
        tx.taskAssignments().insert(assignment);
        tx.agentLeases().insert(lease);
        DomainEventRecord event = DomainEventRecord.create(eventId, "task", task.id(), "TaskCreated",
                "{\"id\":\"" + task.id() + "\"}", now, task.version());
        tx.domainEvents().insert(event);
        tx.outboxEvents().insert(OutboxEventRecord.pending(eventId, "task", task.id(), "TaskCreated",
                "{\"id\":\"" + task.id() + "\"}", now));
        throw new IllegalStateException("intentional rollback");
    }
}
