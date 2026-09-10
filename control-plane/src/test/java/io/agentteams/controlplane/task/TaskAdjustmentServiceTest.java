package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.persistence.CreateTaskAdjustmentCommand;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceAction;
import io.agentteams.controlplane.security.ResourceAuthorizationService;
import io.agentteams.controlplane.service.IdempotencyService;
import io.agentteams.domain.task.TaskPhase;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TaskAdjustmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");

    private final FoundationPersistenceService persistence = mock(FoundationPersistenceService.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final ResourceAuthorizationService authorization = mock(ResourceAuthorizationService.class);

    private final TaskAdjustmentService service =
            new TaskAdjustmentService(persistence, idempotency, Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                    authorization);

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void rejectsBlankRequirementWithoutPersisting() {
        assertThatThrownBy(() -> service.create(UUID.randomUUID(),
                new TaskAdjustmentService.AdjustmentInput("   ", null, null), "key-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requirement");
        verify(persistence, never()).createTaskAdjustment(any());
    }

    @Test
    void rejectsOverlongRequirementWithoutPersisting() {
        String requirement = "x".repeat(TaskAdjustmentService.MAX_REQUIREMENT_LENGTH + 1);
        assertThatThrownBy(() -> service.create(UUID.randomUUID(),
                new TaskAdjustmentService.AdjustmentInput(requirement, null, null), "key-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most");
        verify(persistence, never()).createTaskAdjustment(any());
    }

    @Test
    void wrapsRequirementIntoContentJsonWithDefaults() {
        UUID taskId = UUID.randomUUID();
        when(persistence.findTask(taskId)).thenReturn(java.util.Optional.of(draftTask(taskId)));
        when(idempotency.requestHash(any(), any(), any(), any())).thenReturn("hash-1");
        when(idempotency.requireKey("key-1")).thenReturn("key-1");

        service.create(taskId, new TaskAdjustmentService.AdjustmentInput("  请补充附录 B  ", null, null), "key-1");

        verify(persistence).createTaskAdjustment(org.mockito.ArgumentMatchers.argThat(command -> {
            JsonNode content = readJson(command.contentJson());
            return command.taskId().equals(taskId)
                    && "请补充附录 B".equals(content.path("requirement").asText())
                    && "api".equals(command.actor())
                    && "rest".equals(command.source())
                    && "hash-1".equals(command.requestHash())
                    && command.createdAt().equals(NOW);
        }));
    }

    @Test
    void deniedOperatePermissionDoesNotPersist() {
        UUID taskId = UUID.randomUUID();
        AuthorizationService.Scope scope = new AuthorizationService.Scope("tenant-a", "project-a", "team-a");
        PrincipalContext.set(new Principal("alice", scope, Set.of()));
        when(persistence.findTask(taskId)).thenReturn(java.util.Optional.of(draftTask(taskId)));
        org.mockito.Mockito.doThrow(new AuthorizationException("permission denied: TASK_OPERATE"))
                .when(authorization).require(ResourceAction.TASK_OPERATE, scope);

        assertThatThrownBy(() -> service.create(taskId,
                new TaskAdjustmentService.AdjustmentInput("补充要求", null, null), "key-1"))
                .isInstanceOf(AuthorizationException.class);
        verify(persistence, never()).createTaskAdjustment(any());
    }

    // consumePending 的并入与回填行为由 FoundationRepositoryIT 集成测试覆盖（G02 Task 7）。

    private io.agentteams.controlplane.persistence.TaskRecord draftTask(UUID id) {
        return new TaskRecord(id, "标题", "", TaskPhase.DRAFT, 0, "{}", "alice", "rest",
                null, null, NOW, NOW, 0);
    }

    private static JsonNode readJson(String value) {
        try {
            return new ObjectMapper().readTree(value);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
