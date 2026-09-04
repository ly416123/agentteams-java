package io.agentteams.controlplane.team;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.application.api.ConfigEventPort.ConfigAppliedCommand;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class TeamDeploymentRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");
    private static final long CONFIG_VERSION = 1L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private TeamDeploymentRepository repository;
    private UUID agentId;
    private UUID teamId;
    private UUID bindingId;
    private UUID snapshotId;
    private UUID applyEventId;

    @BeforeEach
    void resetDatabase() {
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false).load().clean();
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new TeamDeploymentRepository(jdbc);

        agentId = UUID.randomUUID();
        teamId = UUID.randomUUID();
        bindingId = UUID.randomUUID();
        snapshotId = UUID.randomUUID();
        applyEventId = UUID.randomUUID();
        // The revision member guard is a DEFERRABLE INITIALLY DEFERRED constraint trigger.
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(ignored -> {
                    seedPublishedRevision();
                    seedConfigPipeline();
                });
    }

    @Test
    void advancesTheSingleDeploymentOwnedByAnAcknowledgedBinding() {
        UUID deploymentId = deploy("deployment-single");

        repository.recordConfigAppliedAck(configApplied(deploymentId));

        assertThat(repository.find(deploymentId).orElseThrow().status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void advancesEveryDeploymentThatSharesOneConfigBinding() {
        UUID first = deploy("deployment-shared-1");
        UUID second = deploy("deployment-shared-2");

        repository.recordConfigAppliedAck(configApplied(first));

        assertThat(repository.find(first).orElseThrow().status()).isEqualTo("SUCCEEDED");
        assertThat(repository.find(second).orElseThrow().status()).isEqualTo("SUCCEEDED");
    }

    /**
     * A Team revision deployment reuses the binding keyed by
     * {@code team-revision:<team>:<revision>:<agent>}, so repeated deployments of the same
     * published revision acknowledge the same binding with one ConfigApplied event.
     */
    private UUID deploy(String idempotencyKey) {
        UUID deploymentId = UUID.randomUUID();
        repository.create(TeamDeployment.create(deploymentId, teamId, 1L,
                List.of(new TeamDeployment.Member(agentId, "{}", "{}", bindingId, "PENDING", null)),
                NOW, idempotencyKey));
        return deploymentId;
    }

    private ConfigAppliedCommand configApplied(UUID deploymentId) {
        return new ConfigAppliedCommand(applyEventId, bindingId, snapshotId, agentId, CONFIG_VERSION, true,
                null, NOW, "test", "ack-" + deploymentId, List.of());
    }

    private void seedPublishedRevision() {
        jdbc.update("""
                INSERT INTO agents(id, name, phase, runtime, capabilities, metadata, created_at, updated_at)
                VALUES (?, ?, 'READY', 'qwenpaw', '{}'::jsonb, '{}'::jsonb, ?, ?)
                """, agentId, "agent-" + agentId, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO teams(id, name, display_name, status, created_at, updated_at, version)
                VALUES (?, ?, 'Team', 'ACTIVE', ?, ?, 0)
                """, teamId, "team-" + teamId, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO team_revisions(team_id, revision, leader_agent_id, overlay, digest, status,
                    rollback_of_revision, created_by, created_at, version, idempotency_key, request_hash)
                VALUES (?, 1, ?, '{}'::jsonb, 'digest', 'PUBLISHED', NULL, 'alice', ?, 0, 'revision-key',
                    'revision-request-hash')
                """, teamId, agentId, Timestamp.from(NOW));
        jdbc.update("INSERT INTO team_revision_members(team_id, team_revision, agent_id, member_index) "
                + "VALUES (?, 1, ?, 0)", teamId, agentId);
    }

    private void seedConfigPipeline() {
        String subject = "team-revision:" + teamId + ":1:" + agentId;
        jdbc.update("""
                INSERT INTO config_snapshots(id, subject, version, manifest, checksum, actor, created_at)
                VALUES (?, ?, ?, '{}'::jsonb, 'checksum', 'alice', ?)
                """, snapshotId, subject, CONFIG_VERSION, Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO config_bindings(id, subject, agent_id, snapshot_id, desired_at)
                VALUES (?, ?, ?, ?, ?)
                """, bindingId, subject, agentId, snapshotId, Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO config_apply_records(id, binding_id, agent_id, snapshot_id, phase, error_message,
                    applied_at, updated_at, observed_version)
                VALUES (?, ?, ?, ?, 'PENDING', NULL, NULL, ?, ?)
                """, applyEventId, bindingId, agentId, snapshotId, Timestamp.from(NOW), CONFIG_VERSION);
    }
}
