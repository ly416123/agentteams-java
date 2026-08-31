package io.agentteams.controlplane.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.controlplane.persistence.JdbcSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcArtifactRetentionRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private JdbcArtifactRetentionRepository repository;

    @BeforeEach
    void resetDatabase() {
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false).load().clean();
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcArtifactRetentionRepository(jdbc);
    }

    @Test
    void resolvesTaskOverrideBeforeProjectPolicyAndFallback() {
        UUID taskId = task("SUCCEEDED");
        UUID artifactId = artifact(taskId, "AVAILABLE", NOW.minusSeconds(1));
        jdbc.update("""
                INSERT INTO resource_scopes(resource_type, resource_id, tenant_id, project_id, team, created_at, updated_at)
                VALUES ('TASK', ?, 'tenant-a', 'project-a', 'team-a', ?, ?)
                """, taskId, JdbcSupport.timestamp(NOW), JdbcSupport.timestamp(NOW));
        ArtifactRetentionPolicy project = new ArtifactRetentionPolicy(Duration.ofDays(1), Duration.ofDays(2),
                Duration.ofDays(3), false);
        ArtifactRetentionPolicy override = new ArtifactRetentionPolicy(Duration.ZERO, Duration.ofDays(4),
                Duration.ofDays(5), true);
        repository.upsertProjectPolicy("tenant-a", "project-a", project, NOW);
        repository.upsertTaskOverride(taskId, override, NOW);
        repository.upsertTaskOverride(taskId, override, NOW.plusSeconds(1));

        var candidates = repository.findExpiredCandidates(NOW,
                new ArtifactRetentionPolicy(Duration.ofDays(9), Duration.ofDays(9), Duration.ofDays(9), false), 10);

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.artifactId()).isEqualTo(artifactId);
            assertThat(candidate.policy()).isEqualTo(override);
            assertThat(candidate.policySource()).isEqualTo("TASK");
            assertThat(candidate.policyVersion()).isEqualTo(1);
        });
    }

    @Test
    void persistsTombstoneAndMarksArtifactDeletedAfterObjectDeletion() {
        UUID taskId = task("SUCCEEDED");
        UUID artifactId = artifact(taskId, "AVAILABLE", NOW.minusSeconds(1));
        ArtifactRetentionCandidate candidate = repository.findExpiredCandidates(NOW,
                new ArtifactRetentionPolicy(Duration.ZERO, Duration.ZERO, Duration.ZERO, false), 10).get(0);

        assertThat(repository.insertTombstone(candidate, "a".repeat(64), NOW, "PENDING", "{}", "test-operator"))
                .isTrue();
        assertThat(repository.findDueTombstones(NOW, 10)).singleElement()
                .satisfies(tombstone -> assertThat(tombstone.artifactId()).isEqualTo(artifactId));
        var tombstone = repository.findDueTombstones(NOW, 10).get(0);
        repository.markDeleted(tombstone.id(), artifactId, NOW);

        assertThat(jdbc.queryForObject("SELECT status FROM artifacts WHERE id = ?", String.class, artifactId))
                .isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("SELECT status FROM artifact_retention_tombstones WHERE id = ?", String.class,
                tombstone.id())).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("SELECT operator FROM artifact_retention_tombstones WHERE id = ?", String.class,
                tombstone.id())).isEqualTo("test-operator");
    }

    private UUID task(String phase) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks(id, title, description, phase, priority, spec, actor, source,
                                  created_at, updated_at, version)
                VALUES (?, 'retention-test', '', ?, 0, '{}'::jsonb, 'test', 'test', ?, ?, 0)
                """, id, phase, JdbcSupport.timestamp(NOW), JdbcSupport.timestamp(NOW));
        return id;
    }

    private UUID artifact(UUID taskId, String status, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO artifacts(id, task_id, name, storage_key, content_type, size_bytes, sha256,
                                      status, metadata, created_at, updated_at, version)
                VALUES (?, ?, 'result.json', ?, 'application/json', 1, ?, ?, '{}'::jsonb, ?, ?, 0)
                """, id, taskId, "tasks/" + taskId + "/result.json", "a".repeat(64), status,
                JdbcSupport.timestamp(createdAt), JdbcSupport.timestamp(createdAt));
        return id;
    }
}
