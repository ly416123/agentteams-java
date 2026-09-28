package io.agentteams.controlplane.conversation;

import io.agentteams.application.api.TimelineItem;
import io.agentteams.controlplane.security.ExecutionContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Merges scoped conversation messages and task facts into a stable timeline. */
@Service
public final class ConversationTimelineService {
    private static final Set<String> DEFAULT_VISIBILITY = Set.of("PUBLIC", "TEAM", "PRIVATE");
    private static final Comparator<TimelineItem> ORDER = Comparator.comparing(TimelineItem::occurredAt)
            .thenComparingLong(TimelineItem::sequence).thenComparing(TimelineItem::itemId);

    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final ConversationTaskEventSource taskEvents;

    public ConversationTimelineService(ConversationRepository conversations, ConversationMessageRepository messages,
            ConversationTaskEventSource taskEvents) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.taskEvents = Objects.requireNonNull(taskEvents, "taskEvents");
    }

    public List<TimelineItem> list(ExecutionContext context, UUID conversationId, long after, int limit) {
        ConversationRepository.requireContext(context);
        Objects.requireNonNull(conversationId, "conversationId");
        if (after < 0) throw new IllegalArgumentException("after must not be negative");
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        if (conversations.find(context, conversationId).isEmpty()) {
            throw new IllegalArgumentException("conversation is not in the requested scope: " + conversationId);
        }
        List<TimelineItem> result = new ArrayList<>();
        messages.messages(context, conversationId, after, limit).stream()
                .filter(message -> DEFAULT_VISIBILITY.contains(message.visibility()))
                .map(ConversationTimelineService::messageItem)
                .forEach(result::add);
        result.addAll(taskEvents.list(context, conversationId, DEFAULT_VISIBILITY, after, limit));
        return result.stream().filter(item -> DEFAULT_VISIBILITY.contains(item.visibility()))
                .sorted(ORDER).limit(limit).toList();
    }

    private static TimelineItem messageItem(ConversationMessageRecord message) {
        String payloadRef = message.contentRef() == null
                ? "conversation-message:" + message.id() : message.contentRef();
        return new TimelineItem(message.id().toString(), "CONVERSATION_MESSAGE", message.conversationId(), null,
                message.messageType(), message.senderId(), message.occurredAt(), message.sequence(),
                message.visibility(), payloadRef, "Conversation message (" + message.messageType() + ")",
                message.sequence());
    }
}
