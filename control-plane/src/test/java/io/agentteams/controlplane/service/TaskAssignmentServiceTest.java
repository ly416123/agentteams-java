package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.controlplane.persistence.AgentLeaseRecord;
import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskAssignmentRecord;
import io.agentteams.controlplane.persistence.TaskAttemptRecord;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.domain.agent.AgentPhase;
import io.agentteams.domain.task.TaskPhase;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class TaskAssignmentServiceTest {

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
    void assignsQueuedTaskToReadyAgentWithAllRequiredCapabilitiesAndWritesTaskAssignedOutbox() {
        UUID taskId = UUID.randomUUID();
        AgentRecord matching = agent(UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "matching-agent", AgentPhase.READY, "{\"gpu\":true,\"python\":true}");
        AgentRecord missingCapability = agent(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "missing-capability-agent", AgentPhase.READY, "{\"gpu\":true}");
        AgentRecord offlineMatch = agent(UUID.fromString("00000000-0000-0000-0000-000000000000"),
                "offline-matching-agent", AgentPhase.OFFLINE, "{\"gpu\":true,\"python\":true}");
        TaskRecord queued = queuedTask(taskId, "{\"requiredCapabilities\":[\"gpu\",\"python\"]}");
        insert(matching, missingCapability, offlineMatch, queued);

        TaskAssignmentService service = new TaskAssignmentService(persistence, LEASE_DURATION);

        TaskAssignmentService.AssignmentResult result = service.queueReadyTask(taskId, START);

        assertThat(result.task().id()).isEqualTo(taskId);
        assertThat(result.task().phase()).isEqualTo(TaskPhase.ASSIGNED);
        assertThat(result.agent().id()).isEqualTo(matching.id());
        assertThat(result.attempt().taskId()).isEqualTo(taskId);
        assertThat(result.attempt().phase()).isEqualTo(TaskPhase.ASSIGNED);
        assertThat(result.assignment().taskId()).isEqualTo(taskId);
        assertThat(result.assignment().agentId()).isEqualTo(matching.id());
        assertThat(result.lease().agentId()).isEqualTo(matching.id());
        assertThat(result.lease().taskAttemptId()).isEqualTo(result.attempt().id());
        assertThat(result.lease().expiresAt()).isEqualTo(START.plus(LEASE_DURATION));

        persistence.inTransaction(tx -> {
            assertThat(tx.taskAttempts().count()).isEqualTo(1);
            assertThat(tx.taskAssignments().count()).isEqualTo(1);
            assertThat(tx.agentLeases().count()).isEqualTo(1);
            assertThat(tx.outboxEvents().eventTypes()).containsExactly("TaskAssigned");
            assertThat(tx.outboxEvents().findByEventId(result.eventId()))
                    .get()
                    .satisfies(event -> assertThat(event.payloadJson())
                            .contains(taskId.toString(), result.attempt().id().toString(),
                                    result.lease().id().toString(), "requiredCapabilities"));
            return null;
        });
    }

    @Test
    void expiresLeaseReleasesAssignmentQueuesTaskAndAllowsASecondAssignment() {
        UUID taskId = UUID.randomUUID();
        AgentRecord ready = agent(UUID.randomUUID(), "recoverable-agent", AgentPhase.READY,
                "{\"gpu\":true}");
        TaskRecord queued = queuedTask(taskId, "{\"requiredCapabilities\":[\"gpu\"]}");
        insert(ready, queued);

        TaskAssignmentService service = new TaskAssignmentService(persistence, LEASE_DURATION);
        TaskAssignmentService.AssignmentResult first = service.queueReadyTask(taskId, START);
        Instant recoveryTime = first.lease().expiresAt().plusSeconds(1);

        int recovered = service.recoverExpiredLeases(recoveryTime);

        assertThat(recovered).isEqualTo(1);
        assertThat(persistence.findTask(taskId)).get().extracting(TaskRecord::phase)
                .isEqualTo(TaskPhase.QUEUED);
        var expiredLease = persistence.inTransaction(tx -> tx.agentLeases().findById(first.lease().id()));
        assertThat(expiredLease)
                .get()
                .satisfies(lease -> {
                    assertThat(lease.status()).isEqualTo("EXPIRED");
                    assertThat(lease.releasedAt()).isEqualTo(recoveryTime);
                });
        var releasedAssignment = persistence.inTransaction(
                tx -> tx.taskAssignments().findById(first.assignment().id()));
        assertThat(releasedAssignment)
                .get()
                .satisfies(assignment -> {
                    assertThat(assignment.phase()).isEqualTo(TaskPhase.CANCELLED);
                    assertThat(assignment.releasedAt()).isEqualTo(recoveryTime);
                });

        TaskAssignmentService.AssignmentResult second = service.queueReadyTask(taskId, recoveryTime);

        assertThat(second.attempt().id()).isNotEqualTo(first.attempt().id());
        assertThat(second.assignment().id()).isNotEqualTo(first.assignment().id());
        assertThat(second.lease().id()).isNotEqualTo(first.lease().id());
        assertThat(second.task().phase()).isEqualTo(TaskPhase.ASSIGNED);
        persistence.inTransaction(tx -> {
            assertThat(tx.taskAttempts().count()).isEqualTo(2);
            assertThat(tx.taskAssignments().count()).isEqualTo(2);
            assertThat(tx.agentLeases().count()).isEqualTo(2);
            assertThat(tx.outboxEvents().eventTypes()).containsExactly("TaskAssigned", "TaskAssigned");
            return null;
        });
    }

    private void insert(AgentRecord... agents) {
        persistence.inTransaction(tx -> {
            for (AgentRecord agent : agents) {
                tx.agents().insert(agent);
            }
            return null;
        });
    }

    private void insert(AgentRecord first, AgentRecord second, AgentRecord third, TaskRecord task) {
        persistence.inTransaction(tx -> {
            tx.agents().insert(first);
            tx.agents().insert(second);
            tx.agents().insert(third);
            tx.tasks().insert(task);
            return null;
        });
    }

    private void insert(AgentRecord agent, TaskRecord task) {
        persistence.inTransaction(tx -> {
            tx.agents().insert(agent);
            tx.tasks().insert(task);
            return null;
        });
    }

    private static AgentRecord agent(UUID id, String name, AgentPhase phase, String capabilitiesJson) {
        return AgentRecord.create(id, name, phase, "fake", capabilitiesJson, START);
    }

    private static TaskRecord queuedTask(UUID id, String specJson) {
        return new TaskRecord(id, "queued-task", "execute the task", TaskPhase.QUEUED, 10, specJson,
                "test", "test", null, null, START, START, 0);
    }
}
