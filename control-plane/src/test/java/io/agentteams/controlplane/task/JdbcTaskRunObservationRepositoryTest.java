package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.controlplane.persistence.JdbcSupport;
import io.agentteams.controlplane.security.ExecutionContext;
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
class JdbcTaskRunObservationRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");
    private static final ExecutionContext CONTEXT = new ExecutionContext(
            "org-1", "tenant-1", "project-1", "team-1", "agent-worker");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private JdbcTaskRunObservationRepository repository;

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
        repository = new JdbcTaskRunObservationRepository(jdbc);
    }

    @Test
    void lateRunningObservationCannotRegressTerminalRun() {
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant completedAt = NOW.plusSeconds(20);

        repository.ensureRun(CONTEXT, taskId, runId, "SUCCEEDED", completedAt);
        repository.ensureRun(CONTEXT, taskId, runId, "RUNNING", NOW);

        assertThat(jdbc.queryForMap("SELECT status, started_at, completed_at, updated_at FROM task_runs WHERE id = ?",
                runId)).satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("SUCCEEDED");
            assertThat(row.get("completed_at")).isEqualTo(JdbcSupport.timestamp(completedAt));
            assertThat(row.get("updated_at")).isEqualTo(JdbcSupport.timestamp(completedAt));
        });
    }

    @Test
    void runIdentityCannotBeReusedAcrossTaskOrTenant() {
        UUID firstTaskId = UUID.randomUUID();
        UUID secondTaskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        repository.ensureRun(CONTEXT, firstTaskId, runId, "RUNNING", NOW);

        assertThatThrownBy(() -> repository.ensureRun(CONTEXT, secondTaskId, runId, "RUNNING", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run task does not match");

        ExecutionContext otherTenant = new ExecutionContext("org-1", "tenant-2", "project-2", "team-2",
                "agent-worker");
        assertThatThrownBy(() -> repository.ensureRun(otherTenant, firstTaskId, runId, "RUNNING", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run scope does not match");
    }
}
