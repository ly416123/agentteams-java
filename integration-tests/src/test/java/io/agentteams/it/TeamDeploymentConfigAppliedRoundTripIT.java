package io.agentteams.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentteams.application.api.ConfigEventPort;
import io.agentteams.application.api.ConfigEventPort.ConfigAppliedCommand;
import io.agentteams.controlplane.ControlPlaneApplication;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import io.agentteams.controlplane.team.TeamDeployment;
import io.agentteams.controlplane.team.TeamDeploymentPendingTimeoutService;
import io.agentteams.controlplane.team.TeamDeploymentService;
import io.agentteams.controlplane.team.TeamRevision;
import io.agentteams.controlplane.team.TeamRevisionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Round-trip acceptance over the Team deployment acknowledgement trunk: a deployment created
 * through the real {@link TeamDeploymentService} facade converges to SUCCEEDED only after
 * ConfigApplied acknowledgements arrive through the application boundary ({@link ConfigEventPort},
 * the same port the durable event consumer uses), and a member whose acknowledgement never
 * arrives is first closed out by the pending-timeout reconciliation and then flipped back by the
 * late acknowledgement.
 */
@Testcontainers(disabledWithoutDocker = true)
class TeamDeploymentConfigAppliedRoundTripIT {

    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");
    private static final String DATABASE_USER = "agentteams";
    private static final String DATABASE_PASSWORD = "agentteams-dev";
    private static final UUID LEADER = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID WORKER = UUID.fromString("60000000-0000-0000-0000-000000000002");
    private static final UUID TEAM_A = UUID.fromString("60000000-0000-0000-0000-000000000003");
    private static final UUID TEAM_B = UUID.fromString("60000000-0000-0000-0000-000000000004");
    private static final UUID TEAM_C = UUID.fromString("60000000-0000-0000-0000-000000000006");
    private static final UUID PROJECT = UUID.fromString("60000000-0000-0000-0000-000000000005");
    private static final Principal PRINCIPAL = new Principal("alice",
            new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of("team:write"));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("agentteams")
            .withUsername(DATABASE_USER)
            .withPassword(DATABASE_PASSWORD);

    private static ConfigurableApplicationContext controlPlane;
    private static JdbcTemplate jdbc;
    private static ResourceScopeRepository resourceScopes;

    @BeforeAll
    static void startControlPlaneAndSeedTheProject() {
        controlPlane = new SpringApplicationBuilder(ControlPlaneApplication.class)
                .run(commandLineProperties());
        jdbc = new JdbcTemplate(controlPlane.getBean(DataSource.class));
        resourceScopes = controlPlane.getBean(ResourceScopeRepository.class);
        seedAgentsProjectAndOnePublishedRevisionPerTeam();
        resourceScopes.bind("TEAM", TEAM_A, PRINCIPAL, NOW);
        resourceScopes.bind("TEAM", TEAM_B, PRINCIPAL, NOW);
        resourceScopes.bind("TEAM", TEAM_C, PRINCIPAL, NOW);
    }

    @BeforeEach
    void setPrincipal() {
        PrincipalContext.set(PRINCIPAL);
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void acknowledgementsArrivingThroughTheConfigEventPortConvergeTheDeployment() {
        TeamDeploymentService deployments = controlPlane.getBean(TeamDeploymentService.class);
        TeamRevisionRepository revisions = controlPlane.getBean(TeamRevisionRepository.class);
        ConfigEventPort configEvents = controlPlane.getBean(ConfigEventPort.class);
        TeamRevision revision = revisions.find(TEAM_A, 1L).orElseThrow();

        TeamDeployment deployment = deployments.deploy(revision, members(), "it-roundtrip", "deploy-roundtrip-1");

        assertEquals("PENDING", deployment.status());
        for (UUID agent : List.of(LEADER, WORKER)) {
            PendingApply apply = pendingApply(deployment.id(), agent);
            configEvents.applied(new ConfigAppliedCommand(apply.eventId(), apply.bindingId(),
                    apply.snapshotId(), agent, apply.observedVersion(), true, null, Instant.now(),
                    "it-roundtrip", "ack-roundtrip-" + agent));
        }
        assertEquals("SUCCEEDED", deployments.find(deployment.id(), TEAM_A).status());
    }

    @Test
    void aLateAcknowledgementFlipsTimedOutMembersBackToSucceeded() {
        TeamDeploymentService deployments = controlPlane.getBean(TeamDeploymentService.class);
        TeamRevisionRepository revisions = controlPlane.getBean(TeamRevisionRepository.class);
        ConfigEventPort configEvents = controlPlane.getBean(ConfigEventPort.class);
        TeamDeploymentPendingTimeoutService timeouts =
                controlPlane.getBean(TeamDeploymentPendingTimeoutService.class);
        TeamRevision revision = revisions.find(TEAM_B, 1L).orElseThrow();

        TeamDeployment deployment = deployments.deploy(revision, members(), "it-late", "deploy-late-1");

        assertEquals("PENDING", deployment.status());
        jdbc.update("""
                UPDATE config_apply_records
                   SET updated_at = NOW() - INTERVAL '2 hours'
                 WHERE binding_id IN (SELECT binding_id FROM team_deployment_members
                                      WHERE deployment_id = ?)
                """, deployment.id());
        timeouts.reconcile(Instant.now(), Duration.ofMinutes(10), 100);

        TeamDeployment timedOut = deployments.find(deployment.id(), TEAM_B);
        assertEquals("FAILED", timedOut.status());
        assertTrue(timedOut.members().stream().allMatch(member -> "FAILED".equals(member.status())));

        for (UUID agent : List.of(LEADER, WORKER)) {
            PendingApply apply = pendingApply(deployment.id(), agent);
            configEvents.applied(new ConfigAppliedCommand(apply.eventId(), apply.bindingId(),
                    apply.snapshotId(), agent, apply.observedVersion(), true, null, Instant.now(),
                    "it-late", "ack-late-" + agent));
        }
        assertEquals("SUCCEEDED", deployments.find(deployment.id(), TEAM_B).status());
    }

    @Test
    void reconciliationRepairsAnAggregateStuckAtPendingAfterEveryMemberSucceeded() {
        TeamDeploymentService deployments = controlPlane.getBean(TeamDeploymentService.class);
        TeamRevisionRepository revisions = controlPlane.getBean(TeamRevisionRepository.class);
        ConfigEventPort configEvents = controlPlane.getBean(ConfigEventPort.class);
        TeamDeploymentPendingTimeoutService timeouts =
                controlPlane.getBean(TeamDeploymentPendingTimeoutService.class);
        TeamRevision revision = revisions.find(TEAM_C, 1L).orElseThrow();

        TeamDeployment deployment = deployments.deploy(revision, members(), "it-repair", "deploy-repair-1");
        for (UUID agent : List.of(LEADER, WORKER)) {
            PendingApply apply = pendingApply(deployment.id(), agent);
            configEvents.applied(new ConfigAppliedCommand(apply.eventId(), apply.bindingId(),
                    apply.snapshotId(), agent, apply.observedVersion(), true, null, Instant.now(),
                    "it-repair", "ack-repair-" + agent));
        }
        assertEquals("SUCCEEDED", deployments.find(deployment.id(), TEAM_C).status());
        // Reproduces the L5 data written by the pre-fix release: the ACKs landed on every member
        // but the aggregate refresh was skipped, leaving the deployment stuck at PENDING.
        jdbc.update("UPDATE team_deployments SET status = 'PENDING' WHERE id = ?", deployment.id());
        assertEquals("PENDING", deployments.find(deployment.id(), TEAM_C).status());

        TeamDeploymentPendingTimeoutService.TimeoutResult result =
                timeouts.reconcile(Instant.now(), Duration.ofMinutes(10), 100);

        assertEquals(1, result.repaired());
        assertEquals("SUCCEEDED", deployments.find(deployment.id(), TEAM_C).status());
    }

    private static List<TeamDeployment.Member> members() {
        return List.of(new TeamDeployment.Member(LEADER, "{}", "{}"),
                new TeamDeployment.Member(WORKER, "{}", "{}"));
    }

    private record PendingApply(UUID eventId, UUID bindingId, UUID snapshotId, long observedVersion) {
    }

    private PendingApply pendingApply(UUID deploymentId, UUID agentId) {
        return jdbc.queryForObject("""
                SELECT a.id, a.binding_id, a.snapshot_id, a.observed_version
                  FROM config_apply_records a
                  JOIN team_deployment_members m
                    ON m.binding_id = a.binding_id AND m.agent_id = a.agent_id
                 WHERE m.deployment_id = ? AND m.agent_id = ?
                """, (rs, row) -> new PendingApply(rs.getObject("id", UUID.class),
                rs.getObject("binding_id", UUID.class), rs.getObject("snapshot_id", UUID.class),
                rs.getLong("observed_version")), deploymentId, agentId);
    }

    private static void seedAgentsProjectAndOnePublishedRevisionPerTeam() {
        for (UUID agent : List.of(LEADER, WORKER)) {
            jdbc.update("""
                    INSERT INTO agents(id, name, phase, runtime, capabilities, metadata, created_at, updated_at)
                    VALUES (?, ?, 'READY', 'qwenpaw', '{}'::jsonb, '{}'::jsonb, ?, ?)
                    """, agent, "agent-" + agent, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        }
        jdbc.update("""
                INSERT INTO projects(id, tenant_id, name, status, created_by, created_at, updated_at, version)
                VALUES (?, 'tenant-a', 'project-a', 'ACTIVE', 'alice', ?, ?, 0)
                """, PROJECT, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO project_memberships(tenant_id, project_id, subject, role, created_at, updated_at, version)
                VALUES ('tenant-a', ?, 'alice', 'OWNER', ?, ?, 0)
                """, PROJECT, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        // A team allows at most one PUBLISHED revision at a time, so each scenario gets its own
        // team with one published revision; the tests then stay independent of execution order.
        for (UUID team : List.of(TEAM_A, TEAM_B, TEAM_C)) {
            jdbc.update("""
                    INSERT INTO teams(id, name, display_name, status, created_at, updated_at, version)
                    VALUES (?, ?, 'Team', 'ACTIVE', ?, ?, 0)
                    """, team, "team-" + team, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
            // The revision trigger requires the member rows (including the leader) to land in the
            // same transaction as the revision itself.
            TransactionTemplate transaction = new TransactionTemplate(
                    new DataSourceTransactionManager(controlPlane.getBean(DataSource.class)));
            transaction.executeWithoutResult(status -> {
                jdbc.update("""
                        INSERT INTO team_revisions(team_id, revision, leader_agent_id, overlay, digest, status,
                            rollback_of_revision, created_by, created_at, version, idempotency_key, request_hash)
                        VALUES (?, 1, ?, '{}'::jsonb, ?, 'PUBLISHED', NULL, 'alice', ?, 0, ?, ?)
                        """, team, LEADER, "digest-" + team, java.sql.Timestamp.from(NOW),
                        "revision-key-" + team, "revision-request-hash-" + team);
                jdbc.update("INSERT INTO team_revision_members(team_id, team_revision, agent_id, member_index) "
                        + "VALUES (?, 1, ?, 0)", team, LEADER);
                jdbc.update("INSERT INTO team_revision_members(team_id, team_revision, agent_id, member_index) "
                        + "VALUES (?, 1, ?, 1)", team, WORKER);
            });
        }
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
