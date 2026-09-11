package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.controlplane.persistence.CreateTaskCommand;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.domain.task.TaskPhase;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** G03 任务 3：依赖 gate 放行、汇总轮触发与 D3 硬约束（规格 §4.1/§4.2）。 */
@Testcontainers(disabledWithoutDocker = true)
class SubtaskGateServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private FoundationPersistenceService persistence;
    private SubtaskDelegationService delegation;
    private SubtaskGateService gate;

    @BeforeEach
    void migrate() {
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        persistence = new FoundationPersistenceService(dataSource);
        delegation = new SubtaskDelegationService(persistence, Clock.systemUTC());
        gate = new SubtaskGateService(persistence, Clock.systemUTC());
    }

    private UUID createMainTask() {
        TaskRecord task = persistence.createTask(new CreateTaskCommand(
                "key-" + UUID.randomUUID(), "parent", "description", "alice", "test",
                "{}", Instant.now()));
        persistence.updateTaskPhase(task.id(), TaskPhase.QUEUED, task.version(), Instant.now());
        return task.id();
    }

    private void setPhase(UUID taskId, TaskPhase phase) {
        TaskRecord current = persistence.findTask(taskId).orElseThrow();
        persistence.updateTaskPhase(taskId, phase, current.version(), Instant.now());
    }

    @Test
    void releasesDraftChildrenWhoseDependenciesSucceeded() {
        UUID taskId = createMainTask();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(a, "A", 1, List.of()),
                new SubtaskService.SubtaskSpec(b, "B", 2, List.of(a))));
        // A 无依赖：第一个 tick 即放行；B 依赖 A：暂不放行
        assertThat(gate.tick(16)).isEqualTo(1);
        assertThat(persistence.findTask(a).orElseThrow().phase()).isEqualTo(TaskPhase.QUEUED);
        assertThat(persistence.findTask(b).orElseThrow().phase()).isEqualTo(TaskPhase.DRAFT);

        setPhase(a, TaskPhase.SUCCEEDED);
        assertThat(gate.releaseReadyChildren(taskId)).isEqualTo(1);
        assertThat(persistence.findTask(b).orElseThrow().phase()).isEqualTo(TaskPhase.QUEUED);
        // 幂等：再次 tick 无 DRAFT 可放行
        assertThat(gate.releaseReadyChildren(taskId)).isZero();
    }

    @Test
    void aggregatesParentOnlyAfterAllChildrenSucceeded() {
        UUID taskId = createMainTask();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(a, "A", 1, List.of()),
                new SubtaskService.SubtaskSpec(b, "B", 2, List.of(a))));
        setPhase(a, TaskPhase.QUEUED);
        setPhase(a, TaskPhase.SUCCEEDED);
        setPhase(b, TaskPhase.QUEUED);
        setPhase(taskId, TaskPhase.SUCCEEDED);  // 主任务第一跑（拆解轮）结束

        // B 仍 QUEUED：不触发汇总
        assertThat(gate.releaseParentForAggregation(taskId)).isFalse();
        assertThat(persistence.findTask(taskId).orElseThrow().phase()).isEqualTo(TaskPhase.SUCCEEDED);

        setPhase(b, TaskPhase.SUCCEEDED);
        assertThat(gate.releaseParentForAggregation(taskId)).isTrue();
        assertThat(persistence.findTask(taskId).orElseThrow().phase()).isEqualTo(TaskPhase.QUEUED);
        // 幂等：主任务已离开 SUCCEEDED，重复触发返回 false
        assertThat(gate.releaseParentForAggregation(taskId)).isFalse();
    }

    @Test
    void aggregateIsSkippedWhenParentStillRunning() {
        UUID taskId = createMainTask();
        delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(UUID.randomUUID(), "A", 1, List.of())));
        // 主任务第一跑还在进行（QUEUED→RUNNING 语义上等价——未到拆解完成态）
        assertThat(gate.releaseParentForAggregation(taskId)).isFalse();
        assertThat(persistence.findTask(taskId).orElseThrow().phase()).isEqualTo(TaskPhase.QUEUED);
    }
}
