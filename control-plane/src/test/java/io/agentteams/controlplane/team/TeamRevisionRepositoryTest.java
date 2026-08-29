package io.agentteams.controlplane.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
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
class TeamRevisionRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private TeamRevisionRepository repository;

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
        jdbc = new JdbcTemplate(dataSource);
        repository = new TeamRevisionRepository(jdbc);
    }

    @Test
    void replaysRollbackResultWithoutComparingResultRevisionToTargetRevision() {
        UUID teamId = UUID.randomUUID();
        UUID leaderAgentId = UUID.randomUUID();
        TeamRevision target = seedPublishedRevision(teamId, leaderAgentId, 2, 4);

        TeamRevision first = repository.createRollback(teamId, target, 4, "alice", NOW, "rollback-key",
                ignored -> { }, "request-hash-1");
        TeamRevision replay = repository.createRollback(teamId, target, 4, "alice", NOW.plusSeconds(1),
                "rollback-key", ignored -> { }, "request-hash-1");

        assertThat(first.revision()).isEqualTo(3);
        assertThat(replay).isEqualTo(first);
        assertThat(repository.findAll(teamId)).extracting(TeamRevision::revision).containsExactly(2L, 3L);
    }

    @Test
    void rejectsRollbackIdempotencyKeyReuseWithDifferentRequestParameters() {
        UUID teamId = UUID.randomUUID();
        UUID leaderAgentId = UUID.randomUUID();
        TeamRevision target = seedPublishedRevision(teamId, leaderAgentId, 2, 4);
        repository.createRollback(teamId, target, 4, "alice", NOW, "rollback-key",
                ignored -> { }, "request-hash-1");

        assertThatThrownBy(() -> repository.createRollback(teamId, target, 5, "alice", NOW, "rollback-key",
                ignored -> { }, "request-hash-2"))
                .isInstanceOf(TeamRevisionConflictException.class)
                .hasMessageContaining("request hash mismatch");
        assertThat(repository.findAll(teamId)).extracting(TeamRevision::revision).containsExactly(2L, 3L);
    }

    private TeamRevision seedPublishedRevision(UUID teamId, UUID leaderAgentId, long revision, long version) {
        UUID membershipId = UUID.randomUUID();
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(status -> {
                    jdbc.update("""
                            INSERT INTO agents(id, name, phase, runtime, capabilities, metadata, created_at, updated_at)
                            VALUES (?, ?, 'READY', 'qwenpaw', '{}'::jsonb, '{}'::jsonb, ?, ?)
                            """, leaderAgentId, "agent-" + leaderAgentId, NOW, NOW);
                    jdbc.update("""
                            INSERT INTO teams(id, name, display_name, status, created_at, updated_at, version)
                            VALUES (?, ?, 'Team', 'ACTIVE', ?, ?, 0)
                            """, teamId, "team-" + teamId, NOW, NOW);
                    jdbc.update("""
                            INSERT INTO team_memberships(id, team_id, agent_id, role, status, joined_at, updated_at, version)
                            VALUES (?, ?, ?, 'LEADER', 'ACTIVE', ?, ?, 0)
                            """, membershipId, teamId, leaderAgentId, NOW, NOW);
                    jdbc.update("""
                            INSERT INTO team_revisions(team_id, revision, leader_agent_id, overlay, digest, status,
                                rollback_of_revision, created_by, created_at, version, idempotency_key, request_hash)
                            VALUES (?, ?, ?, '{}'::jsonb, 'target-digest', 'PUBLISHED', NULL, 'alice', ?, ?,
                                'target-key', 'target-request-hash')
                            """, teamId, revision, leaderAgentId, NOW, version);
                    jdbc.update("""
                            INSERT INTO team_revision_members(team_id, team_revision, agent_id, member_index)
                            VALUES (?, ?, ?, 0)
                            """, teamId, revision, leaderAgentId);
                    jdbc.update("UPDATE teams SET current_revision = ? WHERE id = ?", revision, teamId);
                });
        return repository.find(teamId, revision).orElseThrow();
    }
}
