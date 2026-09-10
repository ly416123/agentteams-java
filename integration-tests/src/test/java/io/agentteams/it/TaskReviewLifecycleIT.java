package io.agentteams.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.ControlPlaneApplication;
import io.agentteams.controlplane.api.CursorPage;
import io.agentteams.controlplane.api.CursorPageRequest;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskListRecord;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.persistence.TaskAdjustmentRecord;
import io.agentteams.controlplane.persistence.TaskResultVersionRecord;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import io.agentteams.controlplane.service.TaskService;
import io.agentteams.controlplane.task.TaskAdjustmentService;
import io.agentteams.controlplane.task.TaskResultManifestService;
import io.agentteams.controlplane.task.TaskResultVersionService;
import io.agentteams.controlplane.task.TaskReviewConflictException;
import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * G02 任务评审闭环全链路验收：真实持久层上的结果版本提交（D2）、评审打回/通过与幂等
 * 重放（D4）、补充要求消费（D5）、归档列表可见性（D6/D7）、spec 浅合并与受限删除（D9/D10）。
 */
@Testcontainers(disabledWithoutDocker = true)
class TaskReviewLifecycleIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DATABASE_USER = "agentteams";
    private static final String DATABASE_PASSWORD = "agentteams-dev";
    private static final UUID PROJECT = UUID.fromString("70000000-0000-0000-0000-000000000001");
    // 真实运行时 principal scope.project 取 projects.id 的字符串形式（findPage 按 scoped_project.id 匹配）
    private static final String PROJECT_REF = PROJECT.toString();
    private static final String SCOPED_SPEC = "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\""
            + PROJECT_REF + "\",\"team\":\"team-a\"}}";
    // kind token claims 形态：scope.project 用项目名而非 id
    private static final String NAME_SCOPED_SPEC =
            "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}";
    private static final Principal PRINCIPAL = new Principal("alice",
            new AuthorizationService.Scope("tenant-a", PROJECT_REF, "team-a"), Set.of());
    private static final ExecutionContext CONTEXT =
            new ExecutionContext("org-1", "tenant-a", PROJECT_REF, "team-a", "alice");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("agentteams")
            .withUsername(DATABASE_USER)
            .withPassword(DATABASE_PASSWORD);

    static ConfigurableApplicationContext controlPlane;
    static JdbcTemplate jdbc;

    TaskService tasks;
    TaskAdjustmentService adjustments;
    TaskResultVersionService results;
    TaskResultManifestService manifests;
    FoundationPersistenceService persistence;

    @BeforeAll
    static void startControlPlaneAndSeedProject() {
        controlPlane = new SpringApplicationBuilder(ControlPlaneApplication.class)
                .run(commandLineProperties());
        jdbc = new JdbcTemplate(controlPlane.getBean(DataSource.class));
        jdbc.update("""
                INSERT INTO projects(id, tenant_id, name, status, created_by, created_at, updated_at, version)
                VALUES (?, 'tenant-a', 'project-a', 'ACTIVE', 'alice', NOW(), NOW(), 0)
                """, PROJECT);
        jdbc.update("""
                INSERT INTO project_memberships(tenant_id, project_id, subject, role, created_at, updated_at, version)
                VALUES ('tenant-a', ?, 'alice', 'OWNER', NOW(), NOW(), 0)
                """, PROJECT);
    }

    @BeforeEach
    void wireServicesAndPrincipal() {
        tasks = controlPlane.getBean(TaskService.class);
        adjustments = controlPlane.getBean(TaskAdjustmentService.class);
        results = controlPlane.getBean(TaskResultVersionService.class);
        manifests = controlPlane.getBean(TaskResultManifestService.class);
        persistence = controlPlane.getBean(FoundationPersistenceService.class);
        PrincipalContext.set(PRINCIPAL);
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void succeededManifestPublishSubmitsResultVersionOne() {
        UUID taskId = createQueuedTask("it-publish-key");
        UUID runId = insertRun(taskId, "SUCCEEDED");

        manifests.publish(CONTEXT, manifest(taskId, runId, "第一版结果"));

        List<TaskResultVersionRecord> versions = results.list(taskId);
        assertEquals(1, versions.size());
        TaskResultVersionRecord v1 = versions.get(0);
        assertEquals(1, v1.seq());
        assertEquals("SUBMITTED", v1.status());
        assertEquals("alice", v1.submittedBy());
        assertEquals(runId, v1.runId());
        assertTrue(v1.submitted());
        assertTrue(v1.contentJson().contains("report.md"));
        // 同 run 重放幂等：不产生第二个版本
        manifests.publish(CONTEXT, manifest(taskId, runId, "第一版结果"));
        assertEquals(1, results.list(taskId).size());
        assertEquals(1, tasks.latestResultSeq(taskId));
    }

    @Test
    void reviewRejectionRequiresCommentAndReturnsForRevision() {
        UUID taskId = createQueuedTask("it-reject-key");
        UUID runId = insertRun(taskId, "SUCCEEDED");
        manifests.publish(CONTEXT, manifest(taskId, runId, "初版"));
        UUID resultId = results.list(taskId).get(0).id();

        // 打回必填意见（D4）
        assertThatThrownBy(() -> results.review(taskId, resultId,
                new TaskResultVersionService.ReviewCommand("REVISION_REQUIRED", "   ", null), null, "it-r-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("comment");

        TaskResultVersionRecord rejected = results.review(taskId, resultId,
                new TaskResultVersionService.ReviewCommand("revision_required", " 缺少验收标准 ", null),
                null, "it-r-key-2");
        assertEquals("REVISION_REQUIRED", rejected.status());
        assertEquals("缺少验收标准", rejected.reviewComment());
        assertEquals("alice", rejected.reviewActor());

        // 已评审结果不可再次评审
        assertThatThrownBy(() -> results.review(taskId, resultId,
                new TaskResultVersionService.ReviewCommand("ACCEPTED", null, null), null, "it-r-key-3"))
                .isInstanceOf(TaskReviewConflictException.class)
                .hasMessageContaining("SUBMITTED");
    }

    @Test
    void retryAfterRejectionProducesResultVersionTwo() {
        UUID taskId = createQueuedTask("it-retry-key");
        completeTask(taskId);
        UUID run1 = insertRun(taskId, "SUCCEEDED");
        manifests.publish(CONTEXT, manifest(taskId, run1, "初版"));
        UUID v1 = results.list(taskId).get(0).id();
        results.review(taskId, v1,
                new TaskResultVersionService.ReviewCommand("REVISION_REQUIRED", "请补充错误场景", null),
                null, "it-rej-key");

        // D4 新转移边：SUCCEEDED → QUEUED 显式重排队
        tasks.retry(taskId, tasks.get(taskId).version(), "it-retry-op-key", "alice", "rest");
        completeTask(taskId);
        UUID run2 = insertRun(taskId, "SUCCEEDED");
        manifests.publish(CONTEXT, manifest(taskId, run2, "修订版"));

        List<TaskResultVersionRecord> versions = results.list(taskId);
        assertEquals(2, versions.size());
        assertEquals(2, versions.get(0).seq());
        assertEquals("SUBMITTED", versions.get(0).status());
        assertEquals(run2, versions.get(0).runId());
        assertEquals("REVISION_REQUIRED", versions.get(1).status());
        assertEquals(2, tasks.latestResultSeq(taskId));
    }

    @Test
    void reviewAcceptanceReplaysIdempotently() {
        UUID taskId = createQueuedTask("it-accept-key");
        UUID runId = insertRun(taskId, "SUCCEEDED");
        manifests.publish(CONTEXT, manifest(taskId, runId, "交付版"));
        UUID resultId = results.list(taskId).get(0).id();

        TaskResultVersionRecord accepted = results.review(taskId, resultId,
                new TaskResultVersionService.ReviewCommand("ACCEPTED", null, null), null, "it-accept-op-key");
        assertEquals("ACCEPTED", accepted.status());
        assertEquals("alice", accepted.reviewActor());

        TaskResultVersionRecord replayed = results.review(taskId, resultId,
                new TaskResultVersionService.ReviewCommand("ACCEPTED", null, null), null, "it-accept-op-key");
        assertEquals(accepted.id(), replayed.id());
        assertEquals(accepted.version(), replayed.version());
        assertEquals("ACCEPTED", replayed.status());

        // 任务聚合事件版本严格递增（游标消费约定）：ResultSubmitted/ResultReviewed 不再与转移事件同号
        List<Long> eventVersions = jdbc.queryForList("""
                SELECT aggregate_version FROM domain_events
                 WHERE aggregate_type = 'task' AND aggregate_id = ?
                 ORDER BY id
                """, Long.class, taskId);
        for (int i = 1; i < eventVersions.size(); i++) {
            assertTrue(eventVersions.get(i) > eventVersions.get(i - 1),
                    "aggregate_version must strictly increase: " + eventVersions);
        }
    }

    @Test
    void adjustmentConsumesPendingOnNewRun() {
        UUID taskId = createQueuedTask("it-adjust-key");
        adjustments.create(taskId,
                new TaskAdjustmentService.AdjustmentInput("补充数据源说明", "alice", "corp-agent"), "it-adj-key");
        List<TaskAdjustmentRecord> pending = adjustments.list(taskId);
        assertEquals(1, pending.size());
        assertNull(pending.get(0).consumedRunId());

        // 幂等重放：同 key 两次创建返回同一条记录（锚点此前误存 taskId 导致重放必 500）
        TaskAdjustmentRecord replayed = adjustments.create(taskId,
                new TaskAdjustmentService.AdjustmentInput("补充数据源说明", "alice", "corp-agent"), "it-adj-key");
        assertEquals(pending.get(0).id(), replayed.id());

        UUID runId = insertRun(taskId, "RUNNING");
        assertEquals(1, adjustments.consumePending(taskId, runId));
        // 同 run 重放幂等：不再重复消费
        assertEquals(0, adjustments.consumePending(taskId, runId));
        assertEquals(runId, adjustments.list(taskId).get(0).consumedRunId());
    }

    @Test
    void cancelWithReasonReplaysIdempotently() {
        UUID taskId = createQueuedTask("it-cancel-reason-key");
        long versionBefore = tasks.get(taskId).version();
        TaskRecord cancelled = tasks.cancel(taskId, versionBefore, "it-cancel-reason-key",
                "alice", "rest", "需求变更");
        assertEquals("CANCELLED", cancelled.phase().name());
        assertTrue(cancelled.specJson().contains("需求变更"));

        // 同 key 重试（同请求体）：任务已 CANCELLED，转移预检失败但幂等回放必须返回首次结果
        TaskRecord replayed = tasks.cancel(taskId, versionBefore, "it-cancel-reason-key",
                "alice", "rest", "需求变更");
        assertEquals(cancelled.id(), replayed.id());
        assertEquals(cancelled.version(), replayed.version());
    }

    @Test
    void archiveRejectsNonTerminalTaskWithConflict() {
        UUID taskId = createQueuedTask("it-archive-conflict-key");
        assertThatThrownBy(() -> tasks.archive(taskId, tasks.get(taskId).version(),
                "it-archive-conflict-op-key", "alice", "rest"))
                .isInstanceOf(TaskReviewConflictException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    void archiveHidesTaskFromDefaultListing() {
        UUID taskId = createQueuedTask("it-archive-key");
        persistence.transitionTask(taskId, TaskPhase.SUCCEEDED, tasks.get(taskId).version(), Instant.now(),
                "it-archive-complete", "it-archive-complete-hash", "COMPLETE_RUN");

        tasks.archive(taskId, tasks.get(taskId).version(), "it-archive-op-key", "alice", "rest");
        assertTrue(tasks.get(taskId).archived());
        assertEquals("SUCCEEDED", tasks.get(taskId).phase().name());

        CursorPageRequest request = new CursorPageRequest(null, 50, null, null);
        assertFalse(contains(tasks.list(request, activeFilter()), taskId));
        assertTrue(contains(tasks.list(request, archivedFilter()), taskId));
        assertTrue(contains(tasks.list(request, allFilter()), taskId));

        tasks.unarchive(taskId, tasks.get(taskId).version(), "it-unarchive-op-key", "alice", "rest");
        assertTrue(contains(tasks.list(request, activeFilter()), taskId));
    }

    @Test
    void patchMergesSpecTopLevelAndDeleteGuardsExecutionHistory() throws Exception {
        UUID taskId = tasks.create("it-patch-key",
                new TaskService.TaskInput("原始标题", "描述", SCOPED_SPEC, "alice", "rest")).id();

        TaskRecord patched = tasks.patch(taskId,
                new TaskService.TaskPatchCommand("新标题", null, 3, JSON.readTree(
                        "{\"a\":2,\"nested\":null,\"b\":7}")),
                tasks.get(taskId).version(), "it-patch-op-key", "alice");
        assertEquals("新标题", patched.title());
        assertEquals(3, patched.priority());
        JsonNode spec = JSON.readTree(patched.specJson());
        assertEquals(2, spec.path("a").asInt());
        assertTrue(spec.path("nested").isMissingNode());
        assertEquals(7, spec.path("b").asInt());

        // D9 安全边界：平台管理键（授权锚点 scope 与生命周期状态）不可经 PATCH 改写
        assertThatThrownBy(() -> tasks.patch(taskId,
                new TaskService.TaskPatchCommand(null, null, null,
                        JSON.readTree("{\"scope\":{\"tenant\":\"tenant-b\",\"project\":\"p\",\"team\":\"t\"}}")),
                tasks.get(taskId).version(), "it-patch-scope-key", "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("managed by the platform");

        // D10：DRAFT 且无执行记录可删
        tasks.delete(taskId, "it-delete-key", "alice");
        assertThatThrownBy(() -> tasks.get(taskId)).isInstanceOf(ResourceNotFoundException.class);

        // D10：有执行记录的 DRAFT 不可删
        UUID guarded = tasks.create("it-guard-create-key",
                new TaskService.TaskInput("守卫", "", SCOPED_SPEC, "alice", "rest")).id();
        insertRun(guarded, "FAILED");
        assertThatThrownBy(() -> tasks.delete(guarded, "it-guard-delete-key", "alice"))
                .isInstanceOf(TaskReviewConflictException.class)
                .hasMessageContaining("execution history");
        assertEquals("DRAFT", tasks.get(guarded).phase().name());

        // D10：非 DRAFT 不可删
        UUID queued = createQueuedTask("it-queued-delete-key");
        assertThatThrownBy(() -> tasks.delete(queued, "it-queued-delete-key", "alice"))
                .isInstanceOf(TaskReviewConflictException.class)
                .hasMessageContaining("only draft");
    }

    @Test
    void projectScopedListingAcceptsPrincipalByProjectName() {
        // 复现 kind token claims 形态（scope.project 为项目名）：创建、findPage、countByPhase 均兼容
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of()));
        UUID taskId;
        try {
            TaskRecord created = tasks.create("it-name-create-key",
                    new TaskService.TaskInput("名字 scope 任务", "G02 IT", NAME_SCOPED_SPEC, "alice", "rest"));
            tasks.queue(created.id(), created.version(), "it-name-scope-key:queue", "alice");
            taskId = created.id();
        } finally {
            PrincipalContext.set(PRINCIPAL);
        }
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of()));
        try {
            CursorPageRequest request = new CursorPageRequest(null, 50, null, null);
            assertTrue(contains(tasks.list(request, activeFilter()), taskId));
            assertTrue(tasks.stats("ACTIVE").getOrDefault("QUEUED", 0L) >= 1);
        } finally {
            PrincipalContext.set(PRINCIPAL);
        }
    }

    @Test
    void deniedOperatePermissionIsRejectedByRoleMatrix() {
        UUID taskId = createQueuedTask("it-denied-key");
        Principal outsider = new Principal("mallory",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of());
        PrincipalContext.set(outsider);
        assertThatThrownBy(() -> tasks.queue(taskId, 0, "it-denied-queue-key", "mallory"))
                .isInstanceOf(AuthorizationException.class);
    }

    private UUID createQueuedTask(String idempotencyKey) {
        TaskRecord created = tasks.create(idempotencyKey,
                new TaskService.TaskInput("评审闭环任务", "G02 IT", SCOPED_SPEC, "alice", "rest"));
        tasks.queue(created.id(), created.version(), idempotencyKey + ":queue", "alice");
        return created.id();
    }

    private void completeTask(UUID taskId) {
        TaskRecord current = tasks.get(taskId);
        persistence.transitionTask(taskId, TaskPhase.SUCCEEDED, current.version(), Instant.now(),
                "it-complete-" + taskId + "-" + current.version(),
                "it-complete-hash-" + taskId + "-" + current.version(), "COMPLETE_RUN");
    }

    private UUID insertRun(UUID taskId, String status) {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task_runs(id, task_id, organization_id, tenant_id, status, created_at, updated_at, version)
                VALUES (?, ?, 'org-1', 'tenant-a', ?, NOW(), NOW(), 0)
                """, runId, taskId, status);
        return runId;
    }

    private static TaskResultManifest manifest(UUID taskId, UUID runId, String summary) {
        return new TaskResultManifest(taskId, runId, "SUCCEEDED", summary,
                List.of(new TaskResultManifest.ArtifactMetadata("report.md", "s3://bucket/report.md",
                        "text/markdown", 128L,
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", 0L, "final", TaskEventVisibility.REQUESTER)));
    }

    private static TaskService.TaskListFilter activeFilter() {
        return new TaskService.TaskListFilter(null, null, null, null, null, null, null);
    }

    private static TaskService.TaskListFilter archivedFilter() {
        return new TaskService.TaskListFilter(null, null, null, null, null, null, null, null, "ARCHIVED");
    }

    private static TaskService.TaskListFilter allFilter() {
        return new TaskService.TaskListFilter(null, null, null, null, null, null, null, null, "ALL");
    }

    private static boolean contains(CursorPage<TaskListRecord> page, UUID id) {
        return page.items().stream().anyMatch(item -> item.id().equals(id));
    }

    private static String[] commandLineProperties() {
        return new String[] {
                "--spring.main.web-application-type=none",
                "--spring.main.banner-mode=off",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + DATABASE_USER,
                "--spring.datasource.password=" + DATABASE_PASSWORD,
                "--agentteams.scheduler.enabled=false",
                "--agentteams.team-sync.enabled=false",
                "--agentteams.nats.enabled=false",
                "--agentteams.storage.enabled=false"
        };
    }
}
