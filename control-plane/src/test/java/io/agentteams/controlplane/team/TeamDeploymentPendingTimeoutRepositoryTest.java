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
class TeamDeploymentPendingTimeoutRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");
    private static final long CONFIG_VERSION = 1L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private TeamDeploymentRepository repository;
    private JdbcTeamDeploymentPendingTimeoutRepository timeout;

    private final UUID teamId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final UUID snapshotId = UUID.randomUUID();
    private final UUID bindingId = UUID.randomUUID();
    private final UUID applyEventId = UUID.randomUUID();

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
        timeout = new JdbcTeamDeploymentPendingTimeoutRepository(jdbc);
        // The revision member guard is a DEFERRABLE INITIALLY DEFERRED constraint trigger.
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(ignored -> {
                    seedPublishedRevision();
                    seedConfigPipeline();
                });
    }

    @Test
    void failsPendingMembersWhoseApplyRecordStoppedBeingRefreshed() {
        UUID deploymentId = deploy("deployment-timeout", NOW.minusSeconds(3600));

        int failed = timeout.failStalePendingMembers(NOW, Instant.parse("2026-09-03T23:00:00Z"), 100);

        assertThat(failed).isEqualTo(1);
        assertThat(memberStatus(deploymentId)).isEqualTo("FAILED");
        assertThat(memberFailureCode(deploymentId)).isEqualTo("APPLY_TIMEOUT");
        assertThat(repository.find(deploymentId).orElseThrow().status()).isEqualTo("FAILED");
    }

    @Test
    void leavesPendingMembersAloneWhileTheApplyRecordIsStillFresh() {
        UUID deploymentId = deploy("deployment-fresh", NOW.minusSeconds(60));
        refreshApplyRecord(NOW.minusSeconds(60));

        int failed = timeout.failStalePendingMembers(NOW, Instant.parse("2026-09-03T23:00:00Z"), 100);

        assertThat(failed).isZero();
        assertThat(memberStatus(deploymentId)).isEqualTo("PENDING");
        assertThat(repository.find(deploymentId).orElseThrow().status()).isEqualTo("PENDING");
    }

    @Test
    void doesNotTouchMembersWhoseApplyRecordAlreadySucceeded() {
        UUID deploymentId = deploy("deployment-succeeded", NOW.minusSeconds(3600));
        repository.recordConfigAppliedAck(configApplied(deploymentId));

        int failed = timeout.failStalePendingMembers(NOW, Instant.parse("2026-09-03T23:00:00Z"), 100);

        assertThat(failed).isZero();
        assertThat(memberStatus(deploymentId)).isEqualTo("SUCCEEDED");
    }

    @Test
    void acceptsALateAcknowledgementForAMemberTimedOutEarlier() {
        UUID deploymentId = deploy("deployment-late-ack", NOW.minusSeconds(3600));
        timeout.failStalePendingMembers(NOW, Instant.parse("2026-09-03T23:00:00Z"), 100);
        assertThat(memberStatus(deploymentId)).isEqualTo("FAILED");

        repository.recordConfigAppliedAck(configApplied(deploymentId));

        assertThat(memberStatus(deploymentId)).isEqualTo("SUCCEEDED");
        assertThat(memberFailureCode(deploymentId)).isNull();
        assertThat(repository.find(deploymentId).orElseThrow().status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void ignoresMembersWithoutABindingOrApplyRecord() {
        UUID deploymentId = UUID.randomUUID();
        repository.create(TeamDeployment.create(deploymentId, teamId, 1L,
                List.of(new TeamDeployment.Member(agentId, "{}", "{}", null, "PENDING", null)),
                NOW, "deployment-no-binding"));

        int failed = timeout.failStalePendingMembers(NOW, Instant.parse("2026-09-03T23:00:00Z"), 100);

        assertThat(failed).isZero();
        assertThat(memberStatus(deploymentId)).isEqualTo("PENDING");
    }

    @Test
    void repairsAnAggregateStuckAtPendingWhenAllMembersAreAlreadyTerminal() {
        UUID deploymentId = deploy("deployment-stuck-aggregate", NOW.minusSeconds(3600));
        repository.recordConfigAppliedAck(configApplied(deploymentId));
        // Data written by the pre-fix release: the ACK landed but the aggregate refresh was
        // skipped, so the deployment stays PENDING even though every member already succeeded.
        repository.updateStatus(deploymentId, "PENDING");

        int repaired = timeout.refreshPendingAggregates(100);

        assertThat(repaired).isEqualTo(1);
        assertThat(repository.find(deploymentId).orElseThrow().status()).isEqualTo("SUCCEEDED");
        // Idempotent: converged data is not reported as repaired again.
        assertThat(timeout.refreshPendingAggregates(100)).isZero();
    }

    @Test
    void doesNotRepairAggregatesThatStillHavePendingMembers() {
        UUID deploymentId = deploy("deployment-still-pending", NOW.minusSeconds(60));
        refreshApplyRecord(NOW.minusSeconds(60));

        int repaired = timeout.refreshPendingAggregates(100);

        assertThat(repaired).isZero();
        assertThat(repository.find(deploymentId).orElseThrow().status()).isEqualTo("PENDING");
    }

    private String memberStatus(UUID deploymentId) {
        return jdbc.queryForObject(
                "SELECT status FROM team_deployment_members WHERE deployment_id = ?", String.class, deploymentId);
    }

    private String memberFailureCode(UUID deploymentId) {
        return jdbc.queryForObject(
                "SELECT failure_code FROM team_deployment_members WHERE deployment_id = ?", String.class, deploymentId);
    }

    private UUID deploy(String idempotencyKey, Instant createdAt) {
        UUID deploymentId = UUID.randomUUID();
        repository.create(TeamDeployment.create(deploymentId, teamId, 1L,
                List.of(new TeamDeployment.Member(agentId, "{}", "{}", bindingId, "PENDING", null)),
                createdAt, idempotencyKey));
        return deploymentId;
    }

    private void refreshApplyRecord(Instant updatedAt) {
        jdbc.update("UPDATE config_apply_records SET updated_at = ? WHERE id = ?",
                Timestamp.from(updatedAt), applyEventId);
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
                """, snapshotId, subject, CONFIG_VERSION, Timestamp.from(NOW.minusSeconds(3700)));
        jdbc.update("""
                INSERT INTO config_bindings(id, subject, agent_id, snapshot_id, desired_at)
                VALUES (?, ?, ?, ?, ?)
                """, bindingId, subject, agentId, snapshotId, Timestamp.from(NOW.minusSeconds(3700)));
        jdbc.update("""
                INSERT INTO config_apply_records(id, binding_id, agent_id, snapshot_id, phase, error_message,
                    applied_at, updated_at, observed_version)
                VALUES (?, ?, ?, ?, 'PENDING', NULL, NULL, ?, ?)
                """, applyEventId, bindingId, agentId, snapshotId, Timestamp.from(NOW.minusSeconds(3700)),
                CONFIG_VERSION);
    }
}
