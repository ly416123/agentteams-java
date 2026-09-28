package io.agentteams.controlplane.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.application.api.TimelineItem;
import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationTimelineServiceTest {
    private static final Instant BASE = Instant.parse("2026-09-28T03:00:00Z");
    private static final UUID CONVERSATION_ID = UUID.randomUUID();
    private static final ExecutionContext CONTEXT =
            new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "alice");

    @Test
    void mergesMessagesAndTaskEventsWithStableOrdering() {
        InMemoryConversations conversations = new InMemoryConversations();
        conversations.record = new ConversationRecord(CONVERSATION_ID, "tenant-1", "project-1", "team-1",
                "timeline", "ACTIVE", "alice", BASE, BASE, 0);
        InMemoryMessages messages = new InMemoryMessages(List.of(
                new ConversationMessageRecord(UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        CONVERSATION_ID, "tenant-1", "USER", "alice", "CONSOLE", "TEXT", "hello", null,
                        "PUBLIC", BASE.plusSeconds(2), 1, "message-1", "trace-1")));
        TimelineItem taskEvent = new TimelineItem("00000000-0000-0000-0000-000000000001", "TASK_EVENT",
                CONVERSATION_ID, UUID.randomUUID(), "TaskCreated", "system", BASE.plusSeconds(2), 1,
                "TEAM", "domain-event:1", "TaskCreated", 1);
        ConversationTimelineService service = new ConversationTimelineService(conversations, messages,
                (context, conversationId, visibility, after, limit) -> List.of(taskEvent));

        List<TimelineItem> timeline = service.list(CONTEXT, CONVERSATION_ID, 0, 100);

        assertThat(timeline).extracting(TimelineItem::itemType)
                .containsExactly("TASK_EVENT", "CONVERSATION_MESSAGE");
        assertThat(timeline).extracting(TimelineItem::itemId)
                .containsExactly("00000000-0000-0000-0000-000000000001",
                        "00000000-0000-0000-0000-000000000002");
    }

    @Test
    void filtersInternalTimelineItemsAndRejectsCrossTenantReads() {
        InMemoryConversations conversations = new InMemoryConversations();
        conversations.record = new ConversationRecord(CONVERSATION_ID, "tenant-1", "project-1", "team-1",
                "timeline", "ACTIVE", "alice", BASE, BASE, 0);
        InMemoryMessages messages = new InMemoryMessages(List.of(
                new ConversationMessageRecord(UUID.randomUUID(), CONVERSATION_ID, "tenant-1", "SYSTEM", "worker",
                        "CONSOLE", "TEXT", "secret", null, "INTERNAL", BASE, 1, "internal", "trace-1")));
        ConversationTimelineService service = new ConversationTimelineService(conversations, messages,
                (context, conversationId, visibility, after, limit) -> List.of());

        assertThat(service.list(CONTEXT, CONVERSATION_ID, 0, 100)).isEmpty();
        ExecutionContext otherTenant = new ExecutionContext("org-1", "tenant-2", "project-1", "team-1", "bob");
        assertThatThrownBy(() -> service.list(otherTenant, CONVERSATION_ID, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class InMemoryConversations implements ConversationRepository {
        private ConversationRecord record;

        @Override
        public ConversationRecord create(ExecutionContext context, CreateConversationCommand command) {
            return record;
        }

        @Override
        public Optional<ConversationRecord> find(ExecutionContext context, UUID id) {
            return Optional.ofNullable(record).filter(item -> item.id().equals(id)
                    && item.tenantId().equals(context.tenantId())
                    && item.projectId().equals(context.projectId())
                    && java.util.Objects.equals(item.teamId(), context.teamId()));
        }

        @Override
        public ConversationRecord archive(ExecutionContext context, UUID id, long expectedVersion) {
            return find(context, id).orElseThrow();
        }
    }

    private record InMemoryMessages(List<ConversationMessageRecord> records)
            implements ConversationMessageRepository {
        @Override
        public ConversationMessageRecord appendMessage(ExecutionContext context,
                AppendConversationMessageCommand command) {
            return records.get(0);
        }

        @Override
        public Optional<ConversationMessageRecord> findByIdempotency(ExecutionContext context, UUID conversationId,
                String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public List<ConversationMessageRecord> messages(ExecutionContext context, UUID conversationId,
                long after, int limit) {
            return records.stream().filter(item -> item.sequence() > after).limit(limit).toList();
        }
    }
}
