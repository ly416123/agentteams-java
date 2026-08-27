package io.agentteams.controlplane.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.controlplane.persistence.AgentLeaseRecord;
import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.IdempotencyConflictException;
import io.agentteams.controlplane.persistence.TaskAttemptRecord;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import io.agentteams.controlplane.service.TaskAssignmentService;
import io.agentteams.controlplane.service.WorkerLifecycleConflictException;
import io.agentteams.domain.agent.AgentPhase;
import io.agentteams.domain.task.TaskPhase;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class WorkerOperationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private FoundationPersistenceService persistence;
    private WorkerOperationService operations;

    @BeforeEach
    void resetDatabase() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        persistence = new FoundationPersistenceService(dataSource);
        operations = new WorkerOperationService(persistence, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                Duration.ofMinutes(2));
    }

    @Test
    void repeatsDrainWithTheSameIdempotencyKeyWithoutCreatingAnotherOperation() {
        UUID agentId = UUID.randomUUID();
        insertAgent(agentId, AgentPhase.READY);

        WorkerOperation first = operations.drain(agentId, 0, "drain-1");
        WorkerOperation repeated = operations.drain(agentId, 0, "drain-1");

        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(repeated.type()).isEqualTo(WorkerOperationType.DRAIN);
        assertThat(repeated.status()).isEqualTo(WorkerOperationStatus.PENDING);
        assertThat(persistence.findAgent(agentId)).get().extracting(AgentRecord::phase)
                .isEqualTo(AgentPhase.DRAINING);
    }

    @Test
    void doesNotTerminateWhileTheWorkerHasAnActiveLease() {
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID leaseId = UUID.randomUUID();
        insertAgent(agentId, AgentPhase.DRAINING);
        persistence.inTransaction(tx -> {
            Instant expiresAt = NOW.plusSeconds(60);
            tx.tasks().insert(new TaskRecord(taskId, "task", "", TaskPhase.ASSIGNED, 0, "{}", "actor", "test",
                    null, null, NOW, NOW, 1));
            tx.taskAttempts().insert(new TaskAttemptRecord(attemptId, taskId, leaseId, TaskPhase.ASSIGNED,
                    expiresAt, null, "scheduler", "control-plane", null, null, NOW, NOW, 1));
            tx.agentLeases().insert(new AgentLeaseRecord(leaseId, agentId, attemptId, NOW, expiresAt, null,
                    "ACTIVE", NOW, NOW, 0));
            return null;
        });

        assertThatThrownBy(() -> operations.terminate(agentId, 0, "terminate-1"))
                .isInstanceOf(WorkerLifecycleConflictException.class)
                .hasMessage("WORKER_HAS_ACTIVE_TASKS");
    }

    @Test
    void drainingWorkerIsExcludedFromNewAssignments() {
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        insertAgent(agentId, AgentPhase.READY);
        persistence.inTransaction(tx -> {
            tx.tasks().insert(new TaskRecord(taskId, "task", "", TaskPhase.QUEUED, 0,
                    "{\"requiredCapabilities\":[\"python\"]}", "actor", "test", null, null, NOW, NOW, 0));
            return null;
        });

        operations.drain(agentId, 0, "drain-2");

        assertThatThrownBy(() -> new TaskAssignmentService(persistence, Duration.ofSeconds(30))
                .queueReadyTask(taskId, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no READY agent");
    }

    @Test
    void rejectsAChangedRequestUsingAnExistingIdempotencyKey() {
        UUID agentId = UUID.randomUUID();
        insertAgent(agentId, AgentPhase.READY);

        operations.drain(agentId, 0, "same-key");

        assertThatThrownBy(() -> operations.terminate(agentId, 0, "same-key"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void doesNotCreateASecondActiveOperationForTheSameWorker() {
        UUID agentId = UUID.randomUUID();
        insertAgent(agentId, AgentPhase.READY);

        operations.drain(agentId, 0, "first-operation");

        assertThatThrownBy(() -> operations.drain(agentId, 1, "second-operation"))
                .isInstanceOf(WorkerLifecycleConflictException.class)
                .hasMessage("WORKER_OPERATION_IN_PROGRESS");
    }

    @Test
    void fencesNewAssignmentsWhileAWorkerRolloutIsActive() {
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        insertAgent(agentId, AgentPhase.READY);
        persistence.inTransaction(tx -> {
            tx.tasks().insert(new TaskRecord(taskId, "task", "", TaskPhase.QUEUED, 0,
                    "{\"requiredCapabilities\":[\"python\"]}", "actor", "test", null, null, NOW, NOW, 0));
            return null;
        });

        operations.rollout(agentId, new WorkerRolloutRequest(0, "rollout-fence-1", "sha256:image",
                "qwenpaw", "config-1", "secret-1"));

        assertThatThrownBy(() -> new TaskAssignmentService(persistence, Duration.ofSeconds(30))
                .queueReadyTask(taskId, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no READY agent");
    }

    @Test
    void recoversAnExpiredOperationLeaseBeforeAcceptingTheNextOperation() {
        UUID agentId = UUID.randomUUID();
        insertAgent(agentId, AgentPhase.READY);

        WorkerOperation drain = operations.drain(agentId, 0, "expired-drain-1");
        WorkerOperationService lateOperations = new WorkerOperationService(persistence,
                java.time.Clock.fixed(NOW.plusSeconds(180), java.time.ZoneOffset.UTC), Duration.ofMinutes(2));

        WorkerOperation terminate = lateOperations.terminate(agentId, 1, "terminate-after-expiry-1");

        WorkerOperationStatus expiredStatus = persistence.inTransaction(tx ->
                tx.workerOperations().findById(drain.id()).orElseThrow().status());
        assertThat(expiredStatus).isEqualTo(WorkerOperationStatus.FAILED);
        assertThat(terminate.status()).isEqualTo(WorkerOperationStatus.PENDING);
    }

    @Test
    void marksACompletedDrainBeforeAcceptingTerminate() {
        UUID agentId = UUID.randomUUID();
        insertAgent(agentId, AgentPhase.READY);

        WorkerOperation drain = operations.drain(agentId, 0, "drain-before-terminate-1");
        WorkerOperation terminate = operations.terminate(agentId, 1, "terminate-after-drain-1");

        WorkerOperationStatus drainStatus = persistence.inTransaction(tx ->
                tx.workerOperations().findById(drain.id()).orElseThrow().status());
        assertThat(drainStatus).isEqualTo(WorkerOperationStatus.DRAINED);
        assertThat(terminate.status()).isEqualTo(WorkerOperationStatus.PENDING);
    }

    @Test
    void commitsExpiredOperationRecoveryEvenWhenAssignmentFailsLater() {
        UUID agentId = UUID.randomUUID();
        insertAgent(agentId, AgentPhase.READY);
        WorkerOperation drain = operations.drain(agentId, 0, "expired-before-assignment-1");
        TaskAssignmentService assignments = new TaskAssignmentService(persistence, Duration.ofSeconds(30));

        assertThatThrownBy(() -> assignments.queueReadyTask(UUID.randomUUID(), NOW.plusSeconds(180)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task does not exist");

        WorkerOperationStatus expiredStatus = persistence.inTransaction(tx ->
                tx.workerOperations().findById(drain.id()).orElseThrow().status());
        assertThat(expiredStatus).isEqualTo(WorkerOperationStatus.FAILED);
    }

    @Test
    void rejectsDrainingAWorkerThatCanNoLongerBeDrained() {
        UUID agentId = UUID.randomUUID();
        insertAgent(agentId, AgentPhase.TERMINATED);

        assertThatThrownBy(() -> operations.drain(agentId, 0, "drain-terminated"))
                .isInstanceOf(WorkerLifecycleConflictException.class)
                .hasMessage("WORKER_NOT_DRAINABLE");
    }

    @Test
    void checksWorkerVisibilityBeforeCreatingAnOperation() {
        UUID agentId = UUID.randomUUID();
        insertAgent(agentId, AgentPhase.READY);
        ResourceScopeRepository scopes = org.mockito.Mockito.mock(ResourceScopeRepository.class);
        WorkerOperationService scopedOperations = new WorkerOperationService(persistence,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC), Duration.ofMinutes(2), scopes);

        scopedOperations.drain(agentId, 0, "scoped-drain");

        org.mockito.Mockito.verify(scopes).requireVisible("WORKER", agentId);
    }

    @Test
    void persistsTheCompleteRolloutRequestAndRequestContext() {
        UUID agentId = UUID.randomUUID();
        insertAgent(agentId, AgentPhase.READY);
        MDC.put("correlationId", "http-correlation-1");
        try {
            WorkerOperation operation = operations.rollout(agentId, new WorkerRolloutRequest(0, "rollout-1",
                    "sha256:image", "qwenpaw", "config-7", "secret-3", "{\"image\":\"old\"}",
                    "requested-owner", "client-correlation"));

            WorkerOperation stored = persistence.inTransaction(tx -> tx.workerOperations()
                    .findById(operation.id()).orElseThrow());
            assertThat(stored.requestedSpecDigest()).isEqualTo("sha256:image");
            assertThat(stored.requestedRuntime()).isEqualTo("qwenpaw");
            assertThat(stored.requestedConfigRevision()).isEqualTo("config-7");
            assertThat(stored.requestedSecretGeneration()).isEqualTo("secret-3");
            assertThat(stored.previousStableSpec()).isEqualTo("{\"image\": \"old\"}");
            assertThat(stored.owner()).isEqualTo("requested-owner");
            assertThat(stored.correlationId()).isEqualTo("http-correlation-1");
        } finally {
            MDC.remove("correlationId");
        }
    }

    @Test
    void movesRolloutToRunningWhenOnlyOperatorConfirmsTheRequestedVersion() {
        UUID agentId = UUID.randomUUID();
        insertAgent(agentId, AgentPhase.READY);
        WorkerOperation operation = operations.rollout(agentId, new WorkerRolloutRequest(0, "dual-confirm-1",
                "sha256:image", "qwenpaw", "config-1", "secret-1"));

        WorkerOperation observed = operations.confirmRollout(operation.id(), operation.version(),
                new WorkerRolloutConfirmation(true, "sha256:image", "qwenpaw", "config-1", "secret-1",
                        false, "", "", "", "", NOW));

        assertThat(observed.status()).isEqualTo(WorkerOperationStatus.RUNNING);
    }

    @Test
    void succeedsRolloutOnlyAfterOperatorAndGatewayConfirmTheSameVersion() {
        UUID agentId = UUID.randomUUID();
        insertAgent(agentId, AgentPhase.READY);
        WorkerOperation operation = operations.rollout(agentId, new WorkerRolloutRequest(0, "dual-confirm-2",
                "sha256:image", "qwenpaw", "config-1", "secret-1"));

        WorkerOperation observed = operations.confirmRollout(operation.id(), operation.version(),
                new WorkerRolloutConfirmation(true, "sha256:image", "qwenpaw", "config-1", "secret-1",
                        true, "sha256:image", "qwenpaw", "config-1", "secret-1", NOW));

        assertThat(observed.status()).isEqualTo(WorkerOperationStatus.SUCCEEDED);
    }

    @Test
    void keepsRolloutRunningWhenGatewayReportsAStaleVersion() {
        UUID agentId = UUID.randomUUID();
        insertAgent(agentId, AgentPhase.READY);
        WorkerOperation operation = operations.rollout(agentId, new WorkerRolloutRequest(0, "dual-confirm-3",
                "sha256:image", "qwenpaw", "config-1", "secret-1"));

        WorkerOperation observed = operations.confirmRollout(operation.id(), operation.version(),
                new WorkerRolloutConfirmation(true, "sha256:image", "qwenpaw", "config-1", "secret-1",
                        true, "sha256:old", "qwenpaw", "config-1", "secret-1", NOW));

        assertThat(observed.status()).isEqualTo(WorkerOperationStatus.RUNNING);
    }

    @Test
    void advancesRolloutFromIndependentOperatorAndGatewayReports() {
        UUID agentId = UUID.randomUUID();
        insertAgent(agentId, AgentPhase.READY);
        WorkerOperation operation = operations.rollout(agentId, new WorkerRolloutRequest(0, "dual-report-1",
                "sha256:image", "qwenpaw", "config-1", "secret-1"));

        WorkerOperation running = operations.confirmOperator(operation.id(), operation.version(),
                new WorkerOperatorObservation(true, "sha256:image", "qwenpaw", "config-1", "secret-1", NOW));
        assertThat(running.status()).isEqualTo(WorkerOperationStatus.RUNNING);

        WorkerOperation succeeded = operations.confirmGateway(running.id(), running.version(),
                new WorkerGatewayObservation(true, "sha256:image", "qwenpaw", "config-1", "secret-1", NOW));
        assertThat(succeeded.status()).isEqualTo(WorkerOperationStatus.SUCCEEDED);
    }

    private void insertAgent(UUID id, AgentPhase phase) {
        persistence.inTransaction(tx -> {
            tx.agents().insert(AgentRecord.create(id, "worker-" + id, phase, "qwenpaw",
                    "{\"python\":true}", NOW));
            return null;
        });
    }
}
