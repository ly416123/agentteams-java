package io.agentteams.controlplane.template;

import static org.assertj.core.api.Assertions.assertThat;

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
class JdbcWorkerTemplateRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private JdbcWorkerTemplateRepository repository;

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
        repository = new JdbcWorkerTemplateRepository(jdbc);
    }

    @Test
    void persistsLifecycleAndInstanceState() {
        UUID templateId = UUID.randomUUID();
        WorkerTemplate template = new WorkerTemplate(templateId, "tenant-a", "project-a", "demo", "Demo",
                null, 0, NOW, NOW, 0);
        assertThat(repository.insertIdempotency("create-key", "create-hash", templateId, NOW)).isTrue();
        repository.insertTemplate(template);

        WorkerTemplateRevision draft = repository.createRevision(templateId, 1, "{}", "digest", "alice", NOW,
                "revision-key");
        assertThat(draft.status()).isEqualTo(TemplateStatus.DRAFT);
        WorkerTemplateRevision review = repository.transition(templateId, 1, 0, TemplateStatus.DRAFT,
                TemplateStatus.REVIEWING, "review-key");
        assertThat(review.status()).isEqualTo(TemplateStatus.REVIEWING);
        WorkerTemplateRevision published = repository.publish(templateId, 1, 1, "publish-key");
        assertThat(published.status()).isEqualTo(TemplateStatus.PUBLISHED);
        assertThat(repository.findTemplate(templateId).orElseThrow().currentPublishedRevision()).isEqualTo(1L);

        WorkerTemplateInstance pending = new WorkerTemplateInstance(UUID.randomUUID(), templateId, 1, null, null,
                "PENDING", 1, "instance-key", "instance-hash", NOW, NOW, 0);
        repository.insertInstance(pending);
        UUID agentSpecId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_specs(id, name, runtime, model_provider, model_name, desired_state,
                    lifecycle_status, spec, created_at, updated_at)
                VALUES (?, ?, 'java', 'openai', 'gpt-test', 'RUNNING', 'DRAFT', '{}'::jsonb, ?, ?)
                """, agentSpecId, "template-spec-" + agentSpecId, java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO agents(id, name, phase, runtime, capabilities, metadata, created_at, updated_at)
                VALUES (?, ?, 'READY', 'java', '{}'::jsonb, '{}'::jsonb, ?, ?)
                """, workerId, "template-worker-" + workerId, java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        WorkerTemplateInstance completed = new WorkerTemplateInstance(pending.id(), templateId, 1,
                agentSpecId, workerId, "SUCCEEDED", 1, "instance-key", "instance-hash", NOW,
                NOW.plusSeconds(1), 1);
        assertThat(repository.updateInstance(completed, 0).status()).isEqualTo("SUCCEEDED");
        assertThat(repository.findInstanceByIdempotency(templateId, "instance-key")).contains(completed);
    }
}
