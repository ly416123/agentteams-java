package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.task.TaskReviewConflictException;
import io.agentteams.domain.task.IllegalTaskTransitionException;
import io.agentteams.domain.task.TaskPhase;
import io.agentteams.domain.task.TaskTransitionService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** G02 Task 5：归档（D6）/元数据补丁（D9）/受限删除（D10）/统计与列表过滤的准入语义。 */
class TaskServiceLifecycleExtensionsTest {

    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FoundationPersistenceService persistence = mock(FoundationPersistenceService.class);

    private final TaskService service = new TaskService(persistence, new IdempotencyService(),
            new TaskTransitionService(), Clock.fixed(NOW, java.time.ZoneOffset.UTC),
            io.agentteams.observability.TaskMetricsPort.noop(), null, null);

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void archiveRejectsNonTerminalTask() {
        UUID taskId = UUID.randomUUID();
        when(persistence.findTask(taskId)).thenReturn(java.util.Optional.of(task(taskId, TaskPhase.QUEUED)));

        assertThatThrownBy(() -> service.archive(taskId, 0, "archive-key", "alice", "rest"))
                .isInstanceOf(TaskReviewConflictException.class)
                .hasMessageContaining("terminal");
        verify(persistence, never()).inTransaction(any());
    }

    @Test
    void archiveRejectsAlreadyArchivedTask() {
        UUID taskId = UUID.randomUUID();
        when(persistence.findTask(taskId))
                .thenReturn(java.util.Optional.of(archivedTask(taskId, TaskPhase.SUCCEEDED)));

        assertThatThrownBy(() -> service.archive(taskId, 0, "archive-key", "alice", "rest"))
                .isInstanceOf(TaskReviewConflictException.class)
                .hasMessageContaining("already archived");
        verify(persistence, never()).inTransaction(any());
    }

    @Test
    void unarchiveRejectsActiveTask() {
        UUID taskId = UUID.randomUUID();
        when(persistence.findTask(taskId)).thenReturn(java.util.Optional.of(task(taskId, TaskPhase.SUCCEEDED)));

        assertThatThrownBy(() -> service.unarchive(taskId, 0, "unarchive-key", "alice", "rest"))
                .isInstanceOf(TaskReviewConflictException.class)
                .hasMessageContaining("not archived");
        verify(persistence, never()).inTransaction(any());
    }

    @Test
    void patchRejectsEmptyCommand() {
        UUID taskId = UUID.randomUUID();
        when(persistence.findTask(taskId)).thenReturn(java.util.Optional.of(task(taskId, TaskPhase.DRAFT)));

        assertThatThrownBy(() -> service.patch(taskId,
                new TaskService.TaskPatchCommand(null, null, null, null), 0, "patch-key", "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
        verify(persistence, never()).inTransaction(any());
    }

    @Test
    void mergeSpecTopLevelReplacesTopLevelKeysAndDropsNulls() throws Exception {
        String current = "{\"a\":1,\"nested\":{\"x\":1}}";

        String merged = TaskService.mergeSpecTopLevel(current,
                JSON.readTree("{\"a\":2,\"nested\":null,\"b\":{\"y\":2}}"));

        assertThat(JSON.readTree(merged)).isEqualTo(
                JSON.readTree("{\"a\":2,\"b\":{\"y\":2}}"));
    }

    @Test
    void mergeSpecTopLevelRejectsNonObjectPatch() throws Exception {
        assertThatThrownBy(() -> TaskService.mergeSpecTopLevel("{}", JSON.readTree("[1]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");
    }

    @Test
    void deleteRejectsNonDraftTask() {
        UUID taskId = UUID.randomUUID();
        when(persistence.findTask(taskId)).thenReturn(java.util.Optional.of(task(taskId, TaskPhase.QUEUED)));

        assertThatThrownBy(() -> service.delete(taskId, "delete-key", "alice"))
                .isInstanceOf(TaskReviewConflictException.class)
                .hasMessageContaining("only draft");
        verify(persistence, never()).inTransaction(any());
    }

    @Test
    void taskListFilterRejectsUnknownArchiveStatus() {
        assertThatThrownBy(() -> new TaskService.TaskListFilter(null, null, null, null, null, null, null, null,
                "FOO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ACTIVE, ARCHIVED or ALL");
    }

    @Test
    void taskListFilterDefaultsToActiveArchive() {
        TaskService.TaskListFilter filter = new TaskService.TaskListFilter(null, null, null, null, null, null,
                null);

        assertThat(filter.archiveStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void statsRequiresAuthentication() {
        assertThatThrownBy(() -> service.stats("ACTIVE"))
                .isInstanceOf(AuthorizationException.class);
        verify(persistence, never()).inTransaction(any());
    }

    @Test
    void statsRejectsUnknownArchiveStatus() {
        assertThatThrownBy(() -> service.stats("FOO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ACTIVE, ARCHIVED or ALL");
        verify(persistence, never()).inTransaction(any());
    }

    @Test
    void cancelWithReasonWritesSpecAndSkipsPlainTransition() {
        UUID taskId = UUID.randomUUID();
        when(persistence.findTask(taskId)).thenReturn(java.util.Optional.of(task(taskId, TaskPhase.QUEUED)));

        service.cancel(taskId, 0, "cancel-key", "alice", "rest", " 需求变更 ");

        verify(persistence).transitionTaskWithSpec(eq(taskId), eq(TaskPhase.CANCELLED),
                contains("cancelReason"), eq(0L), any(), eq("cancel-key"), any(), eq("CANCEL_TASK"), isNull());
        verify(persistence, never()).transitionTask(any(), any(), any(Long.class), any(), any(), any(), any());
    }

    @Test
    void cancelWithReasonRejectsIllegalPhase() {
        UUID taskId = UUID.randomUUID();
        when(persistence.findTask(taskId)).thenReturn(java.util.Optional.of(task(taskId, TaskPhase.PAUSED)));

        assertThatThrownBy(() -> service.cancel(taskId, 0, "cancel-key", "alice", "rest", "需求变更"))
                .isInstanceOf(IllegalTaskTransitionException.class);
        verify(persistence, never()).transitionTaskWithSpec(any(), any(), any(), any(Long.class), any(),
                any(), any(), any(), any());
    }

    @Test
    void cancelWithoutReasonKeepsPlainTransition() {
        UUID taskId = UUID.randomUUID();
        when(persistence.findTask(taskId)).thenReturn(java.util.Optional.of(task(taskId, TaskPhase.QUEUED)));

        service.cancel(taskId, 0, "cancel-key", "alice", "rest", "   ");

        verify(persistence).transitionTask(eq(taskId), eq(TaskPhase.CANCELLED), any(Long.class), any(),
                any(), any(), any());
        verify(persistence, never()).transitionTaskWithSpec(any(), any(), any(), any(Long.class), any(),
                any(), any(), any(), any());
    }

    // 事务内逻辑（归档幂等重放、乐观锁、TaskDeleted 事件先于行删除、hasTaskRun 检查）
    // 由 FoundationRepositoryIT 集成测试覆盖（G02 Task 7）。

    private static TaskRecord task(UUID id, TaskPhase phase) {
        return new TaskRecord(id, "task", "description", phase, 0, "{}", "alice", "rest",
                null, null, NOW, NOW, 0);
    }

    private static TaskRecord archivedTask(UUID id, TaskPhase phase) {
        return new TaskRecord(id, "task", "description", phase, 0, "{}", "alice", "rest",
                null, null, NOW, NOW, 0, "NORMAL", "ARCHIVED", NOW, "bob");
    }
}
