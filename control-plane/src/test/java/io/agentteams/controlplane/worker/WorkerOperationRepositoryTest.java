package io.agentteams.controlplane.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.JdbcSupport;
import io.agentteams.controlplane.persistence.OptimisticLockFailure;
import io.agentteams.domain.agent.AgentPhase;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class WorkerOperationRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private FoundationPersistenceService persistence;

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
    }

    @Test
    void insertsFindsAndUpdatesAnOperationWithOptimisticVersioning() {
        UUID agentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        WorkerOperation operation = WorkerOperation.pending(operationId, agentId, WorkerOperationType.ROLLOUT,
                "sha256:image", "qwenpaw", "config-7", "secret-3", "{\"image\":\"old\"}",
                "rollout-1", 0, "alice", NOW.plusSeconds(120), "correlation-1", NOW);
        insertAgent(agentId);

        WorkerOperation stored = persistence.inTransaction(tx -> {
            tx.workerOperations().insert(operation);
            return tx.workerOperations().findByIdForUpdate(operationId).orElseThrow();
        });

        assertThat(stored.id()).isEqualTo(operation.id());
        assertThat(stored.agentId()).isEqualTo(operation.agentId());
        assertThat(stored.type()).isEqualTo(operation.type());
        assertThat(stored.requestedSpecDigest()).isEqualTo(operation.requestedSpecDigest());
        assertThat(stored.requestedRuntime()).isEqualTo(operation.requestedRuntime());
        assertThat(stored.requestedConfigRevision()).isEqualTo(operation.requestedConfigRevision());
        assertThat(stored.requestedSecretGeneration()).isEqualTo(operation.requestedSecretGeneration());
        assertThat(stored.previousStableSpec()).isEqualTo("{\"image\": \"old\"}");
        assertThat(stored.idempotencyKey()).isEqualTo(operation.idempotencyKey());
        WorkerOperation running = persistence.inTransaction(tx -> tx.workerOperations().updateStatus(operationId,
                WorkerOperationStatus.RUNNING, null, 0, NOW.plusSeconds(1)));
        assertThat(running.status()).isEqualTo(WorkerOperationStatus.RUNNING);
        assertThat(running.version()).isEqualTo(1);

        assertThatThrownBy(() -> persistence.inTransaction(tx -> tx.workerOperations().updateStatus(operationId,
                WorkerOperationStatus.SUCCEEDED, null, 0, NOW.plusSeconds(2))))
                .isInstanceOf(OptimisticLockFailure.class)
                .hasMessageContaining("expected version 0 but was 1");
    }

    @Test
    void rejectsUnicodeEscapedSecretFieldsInRollbackSnapshots() {
        assertThatThrownBy(() -> JdbcSupport.jsonSnapshot("{\"\\u0074oken\":\"secret\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret material");
    }

    private void insertAgent(UUID id) {
        persistence.inTransaction(tx -> {
            tx.agents().insert(AgentRecord.create(id, "worker-" + id, AgentPhase.READY, "qwenpaw",
                    "{}", NOW));
            return null;
        });
    }
}
