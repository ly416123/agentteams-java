package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.controlplane.persistence.CreateTaskCommand;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.task.SubtaskDelegationService;
import io.agentteams.controlplane.task.SubtaskService;
import io.agentteams.domain.task.TaskPhase;
import io.agentteams.domain.task.TaskTransitionService;
import io.agentteams.observability.TaskMetricsPort;
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

/** G03 任务 4：主任务 cancel 级联取消未出队子任务（DRAFT/QUEUED→CANCELLED，RUNNING 不动）。 */
@Testcontainers(disabledWithoutDocker = true)
class SubtaskCascadeCancelIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private FoundationPersistenceService persistence;
    private SubtaskDelegationService delegation;
    private TaskService tasks;

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
        tasks = new TaskService(persistence, new IdempotencyService(), new TaskTransitionService(),
                Clock.systemUTC(), TaskMetricsPort.noop(), null, null);
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
    void cancelCascadesToUnqueuedSubtasksOnly() {
        UUID parentId = createMainTask();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        delegation.plan(parentId, List.of(
                new SubtaskService.SubtaskSpec(a, "A", 1, List.of()),
                new SubtaskService.SubtaskSpec(b, "B", 2, List.of()),
                new SubtaskService.SubtaskSpec(c, "C", 3, List.of())));
        setPhase(b, TaskPhase.QUEUED);
        setPhase(c, TaskPhase.QUEUED);
        setPhase(c, TaskPhase.RUNNING);  // 已出队

        long version = persistence.findTask(parentId).orElseThrow().version();
        tasks.cancel(parentId, version, "cancel-key", "alice", "rest", "user cancel");

        assertThat(persistence.findTask(parentId).orElseThrow().phase()).isEqualTo(TaskPhase.CANCELLED);
        // 未出队（DRAFT/QUEUED）级联取消
        assertThat(persistence.findTask(a).orElseThrow().phase()).isEqualTo(TaskPhase.CANCELLED);
        assertThat(persistence.findTask(b).orElseThrow().phase()).isEqualTo(TaskPhase.CANCELLED);
        // 已出队（RUNNING）等待自然终态
        assertThat(persistence.findTask(c).orElseThrow().phase()).isEqualTo(TaskPhase.RUNNING);

        // 幂等重放：同 cancel key 再次取消不抛异常，子任务不被重复级联
        tasks.cancel(parentId, version, "cancel-key", "alice", "rest", "user cancel");
        assertThat(persistence.findTask(a).orElseThrow().phase()).isEqualTo(TaskPhase.CANCELLED);
        assertThat(persistence.findTask(c).orElseThrow().phase()).isEqualTo(TaskPhase.RUNNING);
    }
}
