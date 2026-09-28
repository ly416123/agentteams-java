package io.agentteams.controlplane.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-28T03:00:00Z");
    private static final ExecutionContext CONTEXT =
            new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "alice");

    @Test
    void createsArchivesAndAppendsWithinTheConversationScope() {
        InMemoryConversations conversations = new InMemoryConversations();
        ConversationService service = new ConversationService(conversations, conversations, new InMemoryTasks());

        ConversationRecord created = service.create(CONTEXT,
                new CreateConversationCommand(UUID.randomUUID(), "incident", NOW));
        ConversationMessageRecord message = service.appendMessage(CONTEXT,
                new AppendConversationMessageCommand(UUID.randomUUID(), created.id(), "USER", "alice", "CONSOLE",
                        "TEXT", "hello", null, "PUBLIC", NOW, "message-1", "trace-1"));

        assertThat(service.find(CONTEXT, created.id())).get().extracting(ConversationRecord::version).isEqualTo(1L);
        assertThat(service.archive(CONTEXT, created.id(), 1L).status()).isEqualTo("ARCHIVED");
        assertThat(conversations.messages).containsExactly(message);
    }

    @Test
    void bindsTwoTasksToOneConversationWithoutChangingTheirPhases() {
        InMemoryConversations conversations = new InMemoryConversations();
        InMemoryTasks tasks = new InMemoryTasks();
        ConversationService service = new ConversationService(conversations, conversations, tasks);
        ConversationRecord conversation = service.create(CONTEXT,
                new CreateConversationCommand(UUID.randomUUID(), "execution", NOW));
        TaskRecord first = task(TaskPhase.RUNNING);
        TaskRecord second = task(TaskPhase.PAUSED);
        tasks.tasks.put(first.id(), first);
        tasks.tasks.put(second.id(), second);

        TaskRecord boundFirst = service.bindTask(CONTEXT, conversation.id(), first.id());
        TaskRecord boundSecond = service.bindTask(CONTEXT, conversation.id(), second.id());

        assertThat(boundFirst.sourceConversationId()).isEqualTo(conversation.id());
        assertThat(boundSecond.sourceConversationId()).isEqualTo(conversation.id());
        assertThat(boundFirst.phase()).isEqualTo(TaskPhase.RUNNING);
        assertThat(boundSecond.phase()).isEqualTo(TaskPhase.PAUSED);
    }

    @Test
    void rejectsBindingOneTaskToASecondConversation() {
        InMemoryConversations conversations = new InMemoryConversations();
        InMemoryTasks tasks = new InMemoryTasks();
        ConversationService service = new ConversationService(conversations, conversations, tasks);
        ConversationRecord first = service.create(CONTEXT, new CreateConversationCommand(UUID.randomUUID(), "first", NOW));
        ConversationRecord second = service.create(CONTEXT, new CreateConversationCommand(UUID.randomUUID(), "second", NOW));
        TaskRecord task = task(TaskPhase.DRAFT);
        tasks.tasks.put(task.id(), task);

        service.bindTask(CONTEXT, first.id(), task.id());

        assertThatThrownBy(() -> service.bindTask(CONTEXT, second.id(), task.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already bound");
    }

    @Test
    void rejectsCrossTenantConversationReadsBeforeAppending() {
        InMemoryConversations conversations = new InMemoryConversations();
        ConversationService service = new ConversationService(conversations, conversations, new InMemoryTasks());
        ConversationRecord conversation = service.create(CONTEXT,
                new CreateConversationCommand(UUID.randomUUID(), "private", NOW));
        ExecutionContext otherTenant = new ExecutionContext("org-1", "tenant-2", "project-1", "team-1", "bob");

        assertThat(service.find(otherTenant, conversation.id())).isEmpty();
        assertThatThrownBy(() -> service.appendMessage(otherTenant,
                new AppendConversationMessageCommand(UUID.randomUUID(), conversation.id(), "USER", "bob", "CONSOLE",
                        "TEXT", "should fail", null, "PUBLIC", NOW, null, "trace-2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TaskRecord task(TaskPhase phase) {
        TaskRecord draft = TaskRecord.draft(UUID.randomUUID(), "task", "", "alice", "test", NOW);
        return new TaskRecord(draft.id(), draft.title(), draft.description(), phase, draft.priority(), draft.specJson(),
                draft.actor(), draft.source(), draft.failureCode(), draft.redactedFailureMessage(), draft.createdAt(),
                draft.updatedAt(), draft.version(), draft.taskType(), draft.archiveStatus(), draft.archivedAt(),
                draft.archiveActor(), draft.parentTaskId(), draft.kind(), draft.sourceConversationId());
    }

    private static final class InMemoryConversations implements ConversationRepository, ConversationMessageRepository {
        private final Map<UUID, ConversationRecord> records = new HashMap<>();
        private final List<ConversationMessageRecord> messages = new ArrayList<>();

        @Override
        public ConversationRecord create(ExecutionContext context, CreateConversationCommand command) {
            ConversationRecord record = new ConversationRecord(command.id(), context.tenantId(), context.projectId(),
                    context.teamId(), command.title(), "ACTIVE", context.subjectId(), command.createdAt(),
                    command.createdAt(), 0);
            records.put(record.id(), record);
            return record;
        }

        @Override
        public Optional<ConversationRecord> find(ExecutionContext context, UUID id) {
            return Optional.ofNullable(records.get(id)).filter(record -> record.tenantId().equals(context.tenantId())
                    && record.projectId().equals(context.projectId())
                    && java.util.Objects.equals(record.teamId(), context.teamId()));
        }

        @Override
        public ConversationRecord archive(ExecutionContext context, UUID id, long expectedVersion) {
            ConversationRecord current = find(context, id).orElseThrow();
            if (current.version() != expectedVersion) throw new IllegalStateException("conversation version mismatch");
            ConversationRecord archived = new ConversationRecord(current.id(), current.tenantId(), current.projectId(),
                    current.teamId(), current.title(), "ARCHIVED", current.createdBy(), current.createdAt(), NOW,
                    current.version() + 1);
            records.put(id, archived);
            return archived;
        }

        @Override
        public ConversationMessageRecord appendMessage(ExecutionContext context,
                AppendConversationMessageCommand command) {
            ConversationRecord current = find(context, command.conversationId()).orElseThrow();
            ConversationMessageRecord message = new ConversationMessageRecord(command.id(), command.conversationId(),
                    context.tenantId(), command.senderType(), command.senderId(), command.channel(), command.messageType(),
                    command.content(), command.contentRef(), command.visibility(), command.occurredAt(),
                    messages.stream().filter(item -> item.conversationId().equals(command.conversationId())).count() + 1,
                    command.idempotencyKey(), command.traceId());
            messages.add(message);
            records.put(current.id(), new ConversationRecord(current.id(), current.tenantId(), current.projectId(),
                    current.teamId(), current.title(), current.status(), current.createdBy(), current.createdAt(),
                    command.occurredAt(), current.version() + 1));
            return message;
        }

        @Override
        public Optional<ConversationMessageRecord> findByIdempotency(ExecutionContext context, UUID conversationId,
                String idempotencyKey) {
            return messages.stream().filter(item -> item.conversationId().equals(conversationId)
                    && java.util.Objects.equals(item.idempotencyKey(), idempotencyKey)).findFirst();
        }

        @Override
        public List<ConversationMessageRecord> messages(ExecutionContext context, UUID conversationId,
                long after, int limit) {
            return messages.stream().filter(item -> item.conversationId().equals(conversationId)
                    && item.sequence() > after).limit(limit).toList();
        }
    }

    private static final class InMemoryTasks implements ConversationTaskRepository {
        private final Map<UUID, TaskRecord> tasks = new HashMap<>();

        @Override
        public TaskRecord bindSourceConversation(ExecutionContext context, UUID taskId, UUID conversationId) {
            TaskRecord current = Optional.ofNullable(tasks.get(taskId)).orElseThrow();
            if (current.sourceConversationId() != null && !current.sourceConversationId().equals(conversationId)) {
                throw new IllegalStateException("task is already bound to a different conversation: " + taskId);
            }
            TaskRecord bound = new TaskRecord(current.id(), current.title(), current.description(), current.phase(),
                    current.priority(), current.specJson(), current.actor(), current.source(), current.failureCode(),
                    current.redactedFailureMessage(), current.createdAt(), NOW, current.version(), current.taskType(),
                    current.archiveStatus(), current.archivedAt(), current.archiveActor(), current.parentTaskId(),
                    current.kind(), conversationId);
            tasks.put(taskId, bound);
            return bound;
        }
    }
}
