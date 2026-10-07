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

    @Test
    void latestRunIdReturnsMostRecentRunOrEmpty() {
        UUID taskId = UUID.randomUUID();
        UUID olderRun = UUID.randomUUID();
        UUID newerRun = UUID.randomUUID();
        repository.ensureRun(CONTEXT, taskId, olderRun, "SUCCEEDED", NOW);
        repository.ensureRun(CONTEXT, taskId, newerRun, "RUNNING", NOW.plusSeconds(30));

        assertThat(repository.latestRunId(taskId)).contains(newerRun);
        assertThat(repository.latestRunId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void taskRunSnapshotIsBoundToOrganizationTenantTaskAndRun() {
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID otherRunId = UUID.randomUUID();
        ExecutionContext scoped = new ExecutionContext("legacy:tenant-1", "tenant-1", "project-1", "team-1", "alice");
        jdbc.update("INSERT INTO tasks(id,title,description,phase,spec,actor,source,created_at,updated_at,version) " +
                "VALUES (?,?,?,?,?::jsonb,?,?,?,?,0)", taskId, "T", "", "SUCCEEDED", "{\"taskType\":\"chat\"}",
                "alice", "api", JdbcSupport.timestamp(NOW), JdbcSupport.timestamp(NOW));
        jdbc.update("INSERT INTO resource_scopes(resource_type,resource_id,tenant_id,project_id,team,created_at,updated_at) " +
                "VALUES ('TASK',?,?,?,?,?,?)", taskId, "tenant-1", "project-1", "team-1",
                JdbcSupport.timestamp(NOW), JdbcSupport.timestamp(NOW));
        repository.ensureRun(scoped, taskId, runId, "FAILED", NOW);
        repository.ensureRun(scoped, taskId, otherRunId, "RUNNING", NOW.plusSeconds(1));
        jdbc.update("UPDATE task_runs SET started_at = ?, completed_at = ? WHERE id = ?",
                JdbcSupport.timestamp(NOW), JdbcSupport.timestamp(NOW.plusSeconds(3)), runId);

        assertThat(repository.snapshotForRun(scoped, taskId, runId)).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.taskPhase()).isEqualTo("SUCCEEDED");
            assertThat(snapshot.runStatus()).isEqualTo("FAILED");
            assertThat(snapshot.startedAt()).isEqualTo(NOW);
            assertThat(snapshot.completedAt()).isEqualTo(NOW.plusSeconds(3));
        });
        assertThat(repository.snapshotForRun(scoped, taskId, otherRunId)).hasValueSatisfying(snapshot ->
                assertThat(snapshot.runStatus()).isEqualTo("RUNNING"));
        assertThat(repository.snapshotForRun(scoped, taskId, UUID.randomUUID())).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.taskPhase()).isEqualTo("SUCCEEDED");
            assertThat(snapshot.runStatus()).isNull();
            assertThat(snapshot.startedAt()).isNull();
            assertThat(snapshot.completedAt()).isNull();
        });
        assertThat(repository.snapshotForRun(new ExecutionContext("legacy:tenant-2", "tenant-2", "project-2", "team-2", "alice"),
                taskId, runId)).isEmpty();
    }
}
