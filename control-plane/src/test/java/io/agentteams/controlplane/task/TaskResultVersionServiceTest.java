package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceAction;
import io.agentteams.controlplane.security.ResourceAuthorizationService;
import io.agentteams.controlplane.service.IdempotencyService;
import io.agentteams.domain.task.TaskPhase;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TaskResultVersionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private static final ExecutionContext CONTEXT =
            new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "worker-1");

    private final FoundationPersistenceService persistence = mock(FoundationPersistenceService.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final ResourceAuthorizationService authorization = mock(ResourceAuthorizationService.class);

    private final TaskResultVersionService service =
            new TaskResultVersionService(persistence, idempotency, Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                    authorization);

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void nonSucceededManifestsDoNotProduceResultVersions() {
        TaskResultManifest failed = new TaskResultManifest(UUID.randomUUID(), UUID.randomUUID(), "FAILED",
                "失败", List.of());

        assertThat(service.onManifestPublished(CONTEXT, failed)).isEmpty();
        verify(persistence, never()).inTransaction(any());
    }

    @Test
    void reviewRejectsUnknownDecision() {
        assertThatThrownBy(() -> service.review(UUID.randomUUID(), UUID.randomUUID(),
                new TaskResultVersionService.ReviewCommand("maybe", null, null), null, "key-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ACCEPTED");
        verify(persistence, never()).inTransaction(any());
    }

    @Test
    void reviewRejectsRevisionWithoutComment() {
        assertThatThrownBy(() -> service.review(UUID.randomUUID(), UUID.randomUUID(),
                new TaskResultVersionService.ReviewCommand("REVISION_REQUIRED", "   ", null), null, "key-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("comment");
        verify(persistence, never()).inTransaction(any());
    }

    @Test
    void deniedApprovalPermissionDoesNotReview() {
        UUID taskId = UUID.randomUUID();
        AuthorizationService.Scope scope = new AuthorizationService.Scope("tenant-a", "project-a", "team-a");
        PrincipalContext.set(new Principal("alice", scope, Set.of()));
        when(persistence.findTask(taskId)).thenReturn(java.util.Optional.of(succeededTask(taskId)));
        doThrow(new AuthorizationException("permission denied: TASK_APPROVE"))
                .when(authorization).require(ResourceAction.TASK_APPROVE, scope);

        assertThatThrownBy(() -> service.review(taskId, UUID.randomUUID(),
                new TaskResultVersionService.ReviewCommand("ACCEPTED", null, null), null, "key-1"))
                .isInstanceOf(AuthorizationException.class);
        verify(persistence, never()).inTransaction(any());
    }

    // onManifestPublished 的事务内逻辑（seq 分配、任务行锁、同 run 幂等、事件）由
    // FoundationRepositoryIT 集成测试覆盖（G02 Task 7）。

    private io.agentteams.controlplane.persistence.TaskRecord succeededTask(UUID id) {
        return new TaskRecord(id, "标题", "", TaskPhase.SUCCEEDED, 0, "{}", "alice", "rest",
                null, null, NOW, NOW, 3);
    }
}
