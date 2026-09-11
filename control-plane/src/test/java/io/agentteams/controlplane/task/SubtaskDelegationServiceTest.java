package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** G03 任务 2：plan 创建 kind=SUBTASK 的一等任务行 + re-plan 声明式同步（规格 §4.1 表）。 */
@Testcontainers(disabledWithoutDocker = true)
class SubtaskDelegationServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private FoundationPersistenceService persistence;
    private SubtaskDelegationService delegation;

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
    }

    private UUID createMainTask(String specJson) {
        TaskRecord task = persistence.createTask(new CreateTaskCommand(
                "key-" + UUID.randomUUID(), "parent", "description", "alice", "test",
                specJson, Instant.now()));
        persistence.updateTaskPhase(task.id(), TaskPhase.QUEUED, task.version(), Instant.now());
        return task.id();
    }

    @Test
    void planCreatesFirstClassSubtaskRows() {
        UUID taskId = createMainTask("{\"scope\":\"tenant-a\"}");
        List<SubtaskDelegationService.PlannedSubtask> nodes = delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(UUID.randomUUID(), "抓取", 1, List.of(), List.of("web")),
                new SubtaskService.SubtaskSpec(UUID.randomUUID(), "摘要", 2, List.of(), List.of()))).planned();

        assertThat(nodes).hasSize(2);
        TaskRecord child = persistence.findTask(nodes.get(0).subtaskId()).orElseThrow();
        assertThat(child.isSubtask()).isTrue();
        assertThat(child.parentTaskId()).isEqualTo(taskId);
        assertThat(child.phase()).isEqualTo(TaskPhase.DRAFT);
        assertThat(child.specJson()).contains("requiredCapabilities")
                .contains("\"web\"")
                .contains("tenant-a")
                .doesNotContain("approvalGranted", "cancelReason");
        // 无能力要求的子任务不写 requiredCapabilities 键
        assertThat(persistence.findTask(nodes.get(1).subtaskId()).orElseThrow().specJson())
                .doesNotContain("requiredCapabilities");
    }

    @Test
    void replanKeepsTerminalAndCancelsStaleUnqueued() {
        UUID taskId = createMainTask("{}");
        UUID childA = UUID.randomUUID();
        UUID childB = UUID.randomUUID();
        delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(childA, "A", 1, List.of()),
                new SubtaskService.SubtaskSpec(childB, "B", 2, List.of())));
        long versionA = persistence.findTask(childA).orElseThrow().version();
        persistence.updateTaskPhase(childA, TaskPhase.QUEUED, versionA, Instant.now());

        UUID childC = UUID.randomUUID();
        List<SubtaskDelegationService.PlannedSubtask> replanned = delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(childA, "A", 1, List.of()),
                new SubtaskService.SubtaskSpec(childC, "C", 3, List.of(childA)))).planned();

        // A 在清单内且未终态：保持 QUEUED，不重置
        assertThat(persistence.findTask(childA).orElseThrow().phase()).isEqualTo(TaskPhase.QUEUED);
        // B 清单外未出队：CANCELLED，行保留可查
        assertThat(persistence.findTask(childB).orElseThrow().phase()).isEqualTo(TaskPhase.CANCELLED);
        // C 新清单项：以 DRAFT 创建，依赖保留
        TaskRecord created = persistence.findTask(childC).orElseThrow();
        assertThat(created.phase()).isEqualTo(TaskPhase.DRAFT);
        assertThat(replanned).extracting(SubtaskDelegationService.PlannedSubtask::subtaskId)
                .containsExactly(childA, childC);
        // 投影保留清单 = 清单内 + 清单外（CANCELLED 行的投影仍在 DAG 上）
        assertThat(replanned).hasSize(2);
        assertThat(persistence.findTask(childB)).isPresent();
    }

    @Test
    void planRejectsDependencyCycle() {
        UUID taskId = createMainTask("{}");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertThatThrownBy(() -> delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(a, "A", 1, List.of(b)),
                new SubtaskService.SubtaskSpec(b, "B", 2, List.of(a)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void planRejectsDuplicateIdsAndOutsidePlanDependencies() {
        UUID taskId = createMainTask("{}");
        UUID same = UUID.randomUUID();
        assertThatThrownBy(() -> delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(same, "重复", 1, List.of()),
                new SubtaskService.SubtaskSpec(same, "重复", 2, List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
        UUID outsider = UUID.randomUUID();
        assertThatThrownBy(() -> delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(UUID.randomUUID(), "抓取", 1, List.of(outsider)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("within the same plan");
    }

    @Test
    void planRejectsSelfReferenceAndNestedPlanning() {
        UUID taskId = createMainTask("{}");
        assertThatThrownBy(() -> delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(taskId, "self", 1, List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");
        // 子任务行下不允许再 plan
        UUID childId = delegation.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(UUID.randomUUID(), "child", 1, List.of())))
                .planned().get(0).subtaskId();
        assertThatThrownBy(() -> delegation.plan(childId, List.of(
                new SubtaskService.SubtaskSpec(UUID.randomUUID(), "grandchild", 1, List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot plan under a subtask");
    }
}
