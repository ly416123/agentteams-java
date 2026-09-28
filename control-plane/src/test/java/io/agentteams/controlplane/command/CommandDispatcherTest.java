package io.agentteams.controlplane.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.CommandActor;
import io.agentteams.application.api.CommandEnvelope;
import io.agentteams.application.api.CommandResult;
import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.conversation.ConversationService;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.service.TaskService;
import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommandDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-09-28T03:00:00Z");
    private static final ExecutionContext CONTEXT =
            new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "alice");
    private static final CommandActor ACTOR = new CommandActor("USER", "alice", "CONSOLE");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void dispatchesTaskStartAndReturnsAccepted() {
        TaskService tasks = mock(TaskService.class);
        InMemoryCommandIdempotencyStore store = new InMemoryCommandIdempotencyStore();
        AuditRecorder audit = mock(AuditRecorder.class);
        UUID taskId = UUID.randomUUID();
        TaskRecord queued = task(taskId, TaskPhase.QUEUED, 1);
        when(tasks.queue(taskId, 0, "command-1", "alice")).thenReturn(queued);
        CommandDispatcher dispatcher = new CommandDispatcher(tasks, mock(ConversationService.class), store, audit, JSON);

        CommandResult result = dispatcher.dispatch(CONTEXT, command(taskId, "command-1", JSON.createObjectNode()));

        assertThat(result.status()).isEqualTo(io.agentteams.application.api.CommandStatus.ACCEPTED);
        assertThat(result.taskId()).isEqualTo(taskId);
        assertThat(result.aggregateVersion()).isEqualTo(1);
        verify(tasks).queue(taskId, 0, "command-1", "alice");
        verify(audit).record(any(AuditEvent.class));
    }

    @Test
    void replaysTheSameCommandAsDuplicateWithoutExecutingItAgain() {
        TaskService tasks = mock(TaskService.class);
        InMemoryCommandIdempotencyStore store = new InMemoryCommandIdempotencyStore();
        AuditRecorder audit = mock(AuditRecorder.class);
        UUID taskId = UUID.randomUUID();
        when(tasks.queue(taskId, 0, "command-1", "alice"))
                .thenReturn(task(taskId, TaskPhase.QUEUED, 1));
        CommandDispatcher dispatcher = new CommandDispatcher(tasks, mock(ConversationService.class), store, audit, JSON);
        CommandEnvelope command = command(taskId, "command-1", JSON.createObjectNode());

        dispatcher.dispatch(CONTEXT, command);
        CommandResult duplicate = dispatcher.dispatch(CONTEXT, command);

        assertThat(duplicate.status()).isEqualTo(io.agentteams.application.api.CommandStatus.DUPLICATE);
        assertThat(duplicate.deduplicated()).isTrue();
        verify(tasks).queue(taskId, 0, "command-1", "alice");
    }

    @Test
    void rejectsSensitiveCommandAndAuditsOnlySafeMetadata() {
        TaskService tasks = mock(TaskService.class);
        InMemoryCommandIdempotencyStore store = new InMemoryCommandIdempotencyStore();
        AuditRecorder audit = mock(AuditRecorder.class);
        UUID taskId = UUID.randomUUID();
        when(tasks.queue(eq(taskId), eq(0L), eq("command-1"), eq("alice")))
                .thenThrow(new AuthorizationException("denied"));
        CommandDispatcher dispatcher = new CommandDispatcher(tasks, mock(ConversationService.class), store, audit, JSON);
        JsonNode payload = JSON.createObjectNode().put("authorization", "Bearer secret-token");

        assertThatThrownBy(() -> dispatcher.dispatch(CONTEXT, command(taskId, "command-1", payload)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sensitive");

        var captured = org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(captured.capture());
        assertThat(captured.getValue().action()).isEqualTo("COMMAND_REJECTED");
        assertThat(captured.getValue().attributes()).doesNotContainKey("payload");
        assertThat(store.records).isEmpty(); // sensitive payload is rejected before claiming idempotency
    }

    private static CommandEnvelope command(UUID taskId, String idempotencyKey, JsonNode payload) {
        return new CommandEnvelope(UUID.randomUUID(), "task.start", "tenant-1", ACTOR, null, taskId, "task", 0,
                payload, idempotencyKey, "trace-1", NOW);
    }

    private static TaskRecord task(UUID id, TaskPhase phase, long version) {
        TaskRecord draft = TaskRecord.draft(id, "task", "", "alice", "test", NOW);
        return new TaskRecord(draft.id(), draft.title(), draft.description(), phase, draft.priority(), draft.specJson(),
                draft.actor(), draft.source(), draft.failureCode(), draft.redactedFailureMessage(), draft.createdAt(),
                draft.updatedAt(), version, draft.taskType(), draft.archiveStatus(), draft.archivedAt(),
                draft.archiveActor(), draft.parentTaskId(), draft.kind(), draft.sourceConversationId());
    }

    private static final class InMemoryCommandIdempotencyStore implements CommandIdempotencyStore {
        private final Map<String, CommandIdempotencyRecord> records = new HashMap<>();

        @Override
        public Optional<CommandIdempotencyRecord> find(ExecutionContext context, String commandType,
                String idempotencyKey) {
            return Optional.ofNullable(records.get(key(context, commandType, idempotencyKey)));
        }

        @Override
        public void save(ExecutionContext context, CommandIdempotencyRecord record) {
            records.put(key(context, record.commandType(), record.idempotencyKey()), record);
        }

        private static String key(ExecutionContext context, String commandType, String idempotencyKey) {
            return context.tenantId() + "\u001f" + commandType + "\u001f" + idempotencyKey;
        }
    }
}
