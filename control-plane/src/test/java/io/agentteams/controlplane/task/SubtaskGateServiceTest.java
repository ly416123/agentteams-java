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

    private UUID createTeamTask() {
        TaskRecord task = persistence.createTask(new CreateTaskCommand(
                "key-" + UUID.randomUUID(), "team-parent", "description", "alice", "test",
                "{\"teamId\":\"team-1\",\"teamRole\":\"LEADER\",\"orchestrationPhase\":\"PLAN\"}",
                Instant.now()));
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
    void injectsSucceededDependencyResultsBeforeReleasingChild() throws Exception {
        UUID taskId = createMainTask();
        UUID first = UUID.randomUUID();
        UUID dependent = UUID.randomUUID();
        delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(first, "Research", 1, List.of()),
                new SubtaskService.SubtaskSpec(dependent, "Summarize research", 2, List.of(first))));
        TeamOrchestrationService orchestration = new TeamOrchestrationService(
                persistence, org.mockito.Mockito.mock(SubtaskService.class), Clock.systemUTC());
        orchestration.recordSubtaskResult(first, "{\"findings\":[\"W3C finding\"]}", Instant.now());
        setPhase(first, TaskPhase.SUCCEEDED);

        assertThat(gate.releaseReadyChildren(taskId)).isEqualTo(1);
        TaskRecord released = persistence.findTask(dependent).orElseThrow();
        assertThat(released.phase()).isEqualTo(TaskPhase.QUEUED);
        var input = new com.fasterxml.jackson.databind.ObjectMapper().readTree(released.specJson()).path("inputJson");
        assertThat(input.path("prompt").asText()).isEqualTo("Summarize research");
        assertThat(input.path("dependencyResults").get(0).path("taskId").asText()).isEqualTo(first.toString());
        assertThat(input.path("dependencyResults").get(0).path("result").asText())
                .isEqualTo("{\"findings\":[\"W3C finding\"]}");
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
        setPhase(taskId, TaskPhase.SUCCEEDED);  // 普通主任务第一跑（拆解轮）结束

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
    void aggregationIsTriggeredOnlyOncePerSubtaskGeneration() {
        UUID taskId = createMainTask();
        UUID a = UUID.randomUUID();
        delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(a, "A", 1, List.of())));
        setPhase(a, TaskPhase.QUEUED);
        setPhase(a, TaskPhase.SUCCEEDED);
        setPhase(taskId, TaskPhase.SUCCEEDED);

        // 拆解轮完成 → 子任务全 SUCCEEDED → 首次聚合放行
        assertThat(gate.releaseParentForAggregation(taskId)).isTrue();
        // 汇总轮跑完：主任务再次回到 SUCCEEDED（子任务仍全 SUCCEEDED）——
        // 触发条件在数值上依然成立，但自动聚合每个子任务代只发生一次，
        // 否则调度 tick 将无限重排汇总轮（kind 验收实测 1s/run 循环）。
        // 并发兜底链条（本测试为顺序重放，未覆盖真并发）：leader lease 串行
        // 化 tick + updatePhase 乐观锁（version 冲突抛 OptimisticLockFailure
        // 整体回滚、含事件 append）——双连接并发触发时只有一方能推进 version。
        setPhase(taskId, TaskPhase.SUCCEEDED);
        assertThat(gate.releaseParentForAggregation(taskId)).isFalse();
        assertThat(persistence.findTask(taskId).orElseThrow().phase())
                .isEqualTo(TaskPhase.SUCCEEDED);
    }

    @Test
    void teamAggregationQueuesParentWithAggregateInput() {
        UUID taskId = createTeamTask();
        UUID child = UUID.randomUUID();
        delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(child, "A", 1, List.of())));
        setPhase(child, TaskPhase.SUCCEEDED);
        setPhase(taskId, TaskPhase.WAITING_SUBTASKS);

        assertThat(TeamOrchestrationService.hasTeam(persistence.findTask(taskId).orElseThrow().specJson()))
                .isTrue();
        assertThat(persistence.findTask(child).orElseThrow().phase()).isEqualTo(TaskPhase.SUCCEEDED);
        assertThat(gate.releaseParentForAggregation(taskId)).isTrue();
        TaskRecord parent = persistence.findTask(taskId).orElseThrow();
        assertThat(parent.phase()).isEqualTo(TaskPhase.QUEUED);
        assertThat(parent.specJson()).contains("\"orchestrationPhase\": \"AGGREGATE\"");
    }

    @Test
    void teamAggregationQueuesParentFromWaitingForSubtasks() {
        UUID taskId = createTeamTask();
        UUID child = UUID.randomUUID();
        delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(child, "A", 1, List.of())));
        setPhase(child, TaskPhase.SUCCEEDED);
        setPhase(taskId, TaskPhase.WAITING_SUBTASKS);

        assertThat(gate.releaseParentForAggregation(taskId)).isTrue();
        assertThat(persistence.findTask(taskId).orElseThrow().phase()).isEqualTo(TaskPhase.QUEUED);
    }

    @Test
    void teamAggregationRemovesPlanningToolRequirementFromAggregateInput() {
        TaskRecord created = persistence.createTask(new CreateTaskCommand(
                "key-" + UUID.randomUUID(), "team-parent-with-tool", "description", "alice", "test",
                "{\"teamId\":\"team-1\",\"teamRole\":\"LEADER\","
                        + "\"orchestrationPhase\":\"PLAN\","
                        + "\"inputJson\":{\"prompt\":\"plan\","
                        + "\"aggregatePrompt\":\"create pdf report\","
                        + "\"requiredMcpTools\":[\"plan_subtasks\"],"
                        + "\"aggregateMcpTools\":[\"create_task_pdf_artifact\"]}}",
                Instant.now()));
        persistence.updateTaskPhase(created.id(), TaskPhase.QUEUED, created.version(), Instant.now());
        UUID child = UUID.randomUUID();
        delegation.plan(created.id(), List.of(
                new SubtaskService.SubtaskSpec(child, "A", 1, List.of())));
        setPhase(child, TaskPhase.SUCCEEDED);
        setPhase(created.id(), TaskPhase.WAITING_SUBTASKS);

        assertThat(gate.releaseParentForAggregation(created.id())).isTrue();
        String inputJson = persistence.findTask(created.id()).orElseThrow().specJson();
        assertThat(inputJson).contains("\"orchestrationPhase\": \"AGGREGATE\"");
        assertThat(inputJson).contains("\"prompt\": \"create pdf report\"");
        assertThat(inputJson).contains("\"requiredMcpTools\": [\"create_task_pdf_artifact\"]");
        assertThat(inputJson).doesNotContain("aggregateMcpTools", "aggregatePrompt", "plan_subtasks");
    }

    @Test
    void leaderPlanMovesParentToWaitingInsteadOfTerminalSuccess() {
        UUID taskId = createTeamTask();
        UUID child = UUID.randomUUID();
        delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(child, "A", 1, List.of())));
        setPhase(taskId, TaskPhase.SUCCEEDED);

        TeamOrchestrationService orchestration = new TeamOrchestrationService(
                persistence, org.mockito.Mockito.mock(SubtaskService.class), Clock.systemUTC());

        assertThat(orchestration.markWaitingForSubtasks(taskId)).isTrue();
        assertThat(persistence.findTask(taskId).orElseThrow().phase())
                .isEqualTo(TaskPhase.WAITING_SUBTASKS);
        assertThat(orchestration.markWaitingForSubtasks(taskId)).isFalse();
    }

    @Test
    void skipsDraftChildrenUnderCancelledParent() {
        UUID taskId = createMainTask();
        UUID a = UUID.randomUUID();
        delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(a, "A", 1, List.of())));
        // 模拟 cancel 级联未达的残留（如级联事务失败）：parent 已终态（CANCELLED
        // 不可恢复），DRAFT 子任务仍在——gate 不再放行，否则子任务凭空消耗
        // worker 配额且产物无汇总去处。
        setPhase(taskId, TaskPhase.CANCELLED);
        assertThat(gate.tick(16)).isZero();
        assertThat(persistence.findTask(a).orElseThrow().phase()).isEqualTo(TaskPhase.DRAFT);
    }

    @Test
    void failsDraftChildrenWhoseDependencyIsDeadTerminal() {
        UUID taskId = createMainTask();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(a, "A", 1, List.of()),
                new SubtaskService.SubtaskSpec(b, "B", 2, List.of(a))));
        // A 进入不可恢复终态（CANCELLED）：B 的依赖条件永远无法满足——置 FAILED
        // （G02 retry 边可恢复、失败可见）而非永久 DRAFT 滞留。
        setPhase(a, TaskPhase.CANCELLED);
        assertThat(gate.releaseReadyChildren(taskId)).isEqualTo(1);
        assertThat(persistence.findTask(b).orElseThrow().phase()).isEqualTo(TaskPhase.FAILED);
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
