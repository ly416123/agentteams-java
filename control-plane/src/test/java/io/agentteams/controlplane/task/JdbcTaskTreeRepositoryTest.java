package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcTaskTreeRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final ExecutionContext CONTEXT = new ExecutionContext(
            "org-1", "tenant-1", "project-1", "team-1", "agent-worker");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private JdbcTaskTreeRepository repository;
    private JdbcTaskRunObservationRepository runs;

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
        repository = new JdbcTaskTreeRepository(jdbc);
        runs = new JdbcTaskRunObservationRepository(jdbc);
    }

    @Test
    void deleteOthersRemovesStaleSubtasksAndKeepsDeclaredOnes() {
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        UUID a3 = UUID.randomUUID();
        runs.ensureRun(CONTEXT, taskId, runId, "RUNNING", NOW);
        upsert(a1, taskId, runId, 1);
        upsert(a2, taskId, runId, 2);
        upsert(a3, taskId, runId, 3);

        int deleted = repository.deleteOthers(CONTEXT, runId, List.of(a2, a3));

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.find(CONTEXT, runId))
                .extracting(TaskTreeNode::taskId)
                .containsExactlyInAnyOrder(a2, a3);
    }

    @Test
    void deleteOthersWithEmptyKeepListClearsTheRun() {
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        runs.ensureRun(CONTEXT, taskId, runId, "RUNNING", NOW);
        upsert(UUID.randomUUID(), taskId, runId, 1);

        assertThat(repository.deleteOthers(CONTEXT, runId, List.of())).isEqualTo(1);
        assertThat(repository.find(CONTEXT, runId)).isEmpty();
    }

    @Test
    void deleteOthersRespectsTenantOwnership() {
        ExecutionContext other = new ExecutionContext("org-2", "tenant-2", "project-2", "team-2", "agent-worker");
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID stale = UUID.randomUUID();
        runs.ensureRun(CONTEXT, taskId, runId, "RUNNING", NOW);
        upsert(stale, taskId, runId, 1);

        // 归属不匹配的删除请求不触碰数据。
        assertThat(repository.deleteOthers(other, runId, List.of())).isZero();
        assertThat(repository.find(CONTEXT, runId)).hasSize(1);
    }

    private void upsert(UUID subtaskId, UUID taskId, UUID runId, int sequence) {
        repository.upsert(CONTEXT, runId,
                new TaskTreeNode(subtaskId, taskId, sequence, "PENDING", List.of(), NOW));
    }
}
