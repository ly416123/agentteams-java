package io.agentteams.controlplane.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcScheduledTaskRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");
    private static final ScheduledTaskScope SCOPE = new ScheduledTaskScope("org-1", "tenant-1", "project-1");

    @BeforeAll
    static void migrate() {
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM scheduled_tasks");
    }

    @Test
    void persistsDueScheduleAndAdvancesItsExecutionCursor() {
        JdbcScheduledTaskRepository repository = new JdbcScheduledTaskRepository(jdbc);
        UUID id = UUID.randomUUID();
        ScheduledTaskDefinition definition = new ScheduledTaskDefinition(id, "report", SCOPE,
                "0 0/5 * * * *", "UTC", "Report", "desc", "{}", "manager", "scheduler", true,
                NOW, null, null, 0, NOW, NOW);
        repository.insert(definition);

        assertThat(repository.findDue(NOW, 10)).extracting(ScheduledTaskDefinition::id).containsExactly(id);
        UUID taskId = UUID.randomUUID();
        jdbc.update("INSERT INTO tasks (id, title, description, phase, priority, spec, actor, source, created_at, updated_at, version) VALUES (?, ?, ?, 'DRAFT', 0, '{}'::jsonb, 'scheduler', 'scheduler', ?, ?, 0)",
                taskId, "Report", "desc", java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        assertThat(repository.advance(id, NOW, taskId, NOW.plusSeconds(300), NOW)).isTrue();
        assertThat(repository.find(SCOPE, id).orElseThrow().lastTaskId()).isEqualTo(taskId);
        assertThat(repository.findDue(NOW, 10)).isEmpty();
    }

    @Test
    void transitionIsScopeBoundAndSafeWhenAlreadyInTargetState() {
        JdbcScheduledTaskRepository repository = new JdbcScheduledTaskRepository(jdbc);
        UUID id = UUID.randomUUID();
        repository.insert(new ScheduledTaskDefinition(id, "report", SCOPE, "0 0 * * * *", "UTC", "Report", "desc",
                "{}", "manager", "scheduler", true, NOW.plusSeconds(3600), null, null, 0, NOW, NOW));

        ScheduledTaskDefinition paused = repository.transition(SCOPE, id, true, false, "pause-1", NOW);
        assertThat(paused.enabled()).isFalse();
        assertThat(repository.transition(SCOPE, id, true, false, "pause-2", NOW)).isEqualTo(paused);
        assertThat(repository.find(new ScheduledTaskScope("org-1", "tenant-2", "project-1"), id)).isEmpty();
    }
}
