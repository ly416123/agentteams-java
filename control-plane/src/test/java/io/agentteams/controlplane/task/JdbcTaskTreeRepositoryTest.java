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

    @Test
    void deleteOthersPreservesTheManagerRootProjectionRow() {
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID stale = UUID.randomUUID();
        runs.ensureRun(CONTEXT, taskId, runId, "RUNNING", NOW);
        // 一期 projectManagerPlan 的单根投影：taskId 自己为根、parent 为空。
        repository.upsert(CONTEXT, runId,
                new TaskTreeNode(taskId, null, 0, "PENDING", List.of(), NOW));
        upsert(stale, taskId, runId, 1);

        int deleted = repository.deleteOthers(CONTEXT, runId, List.of());

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.find(CONTEXT, runId))
                .extracting(TaskTreeNode::taskId)
                .containsExactly(taskId);
    }

    @Test
    void findLatestForParentUsesNewestRunThatContainsChildren() {
        UUID taskId = UUID.randomUUID();
        UUID firstRunId = UUID.randomUUID();
        UUID aggregationRunId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        runs.ensureRun(CONTEXT, taskId, firstRunId, "RUNNING", NOW);
        upsert(childId, taskId, firstRunId, 1);
        runs.ensureRun(CONTEXT, taskId, aggregationRunId, "RUNNING", NOW.plusSeconds(1));
        repository.upsert(CONTEXT, aggregationRunId,
                new TaskTreeNode(taskId, null, 0, "PENDING", List.of(), NOW.plusSeconds(1)));

        assertThat(repository.findLatestForParent(CONTEXT, taskId))
                .extracting(TaskTreeNode::taskId)
                .containsExactly(childId);
    }

    @Test
    void findUsesTaskPhaseAsTheCurrentSubtaskStatus() {
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        runs.ensureRun(CONTEXT, taskId, runId, "SUCCEEDED", NOW);
        upsert(childId, taskId, runId, 1);
        insertTask(taskId, "SUCCEEDED", "{}", null);
        insertTask(childId, "SUCCEEDED", "{}", taskId);

        assertThat(repository.find(CONTEXT, runId)).singleElement()
                .extracting(TaskTreeNode::status).isEqualTo("SUCCEEDED");
    }

    @Test
    void findProjectsOnlyDependenciesWhoseResultsWereHandedToTheSubtask() {
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        runs.ensureRun(CONTEXT, taskId, runId, "RUNNING", NOW);
        String dependencyResults = "{\"dependencyIds\":[\"" + dependencyId + "\"],"
                + "\"inputJson\":{\"dependencyResults\":[{\"taskId\":\"" + dependencyId
                + "\",\"result\":\"verified output\"}]}}";
        insertTask(taskId, "RUNNING", "{}", null);
        insertTask(dependencyId, "SUCCEEDED", "{}", taskId);
        insertTask(childId, "QUEUED", dependencyResults, taskId);
        repository.upsert(CONTEXT, runId,
                new TaskTreeNode(childId, taskId, 1, "PENDING", List.of(dependencyId), NOW));

        TaskTreeNode projected = repository.find(CONTEXT, runId).getFirst();

        assertThat(projected.dependencyResultRefs())
                .containsEntry(dependencyId, "task-result:" + dependencyId);
    }

    private void insertTask(UUID id, String phase, String spec, UUID parentId) {
        jdbc.update("""
                INSERT INTO tasks (id, title, phase, spec, actor, source, parent_task_id, created_at, updated_at)
                VALUES (?, 'task', ?, ?::jsonb, 'test', 'test', ?, ?, ?)
                """, id, phase, spec, parentId, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
    }

    private void upsert(UUID subtaskId, UUID taskId, UUID runId, int sequence) {
        repository.upsert(CONTEXT, runId,
                new TaskTreeNode(subtaskId, taskId, sequence, "PENDING", List.of(), NOW));
    }
}
