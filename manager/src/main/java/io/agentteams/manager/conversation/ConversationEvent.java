package io.agentteams.manager.conversation;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Immutable event in a conversation's replayable event stream. */
public record ConversationEvent(UUID sessionId, long cursor, String type, String data, Instant occurredAt,
        String sourceEventId) {
    private static final Set<String> TYPES = Set.of(
            "conversation.started", "message.delta", "message.completed", "task.created", "task.updated",
            "tool.started", "tool.completed", "conversation.cancelled", "conversation.failed",
            "conversation.recovery_required");

    public ConversationEvent {
        if (cursor <= 0) {
            throw new IllegalArgumentException("cursor must be positive");
        }
        if (type == null || !TYPES.contains(type)) {
            throw new IllegalArgumentException("event type is not supported: " + type);
        }
        if (data == null) {
            throw new IllegalArgumentException("event data must not be null");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
        if (sourceEventId != null && sourceEventId.isBlank()) {
            throw new IllegalArgumentException("sourceEventId must not be blank when supplied");
        }
    }

    public ConversationEvent(UUID sessionId, long cursor, String type, String data, Instant occurredAt) {
        this(sessionId, cursor, type, data, occurredAt, null);
    }

    public static ConversationEvent of(long cursor, String type, String data) {
        return new ConversationEvent(null, cursor, type, data, Instant.EPOCH);
    }

    public static ConversationEvent of(UUID sessionId, long cursor, String type, String data,
            Instant occurredAt) {
        return new ConversationEvent(sessionId, cursor, type, data, occurredAt);
    }

    public static ConversationEvent of(UUID sessionId, long cursor, String type, String data,
            Instant occurredAt, String sourceEventId) {
        return new ConversationEvent(sessionId, cursor, type, data, occurredAt, sourceEventId);
    }

    public ConversationEvent next(String nextType, String nextData) {
        return new ConversationEvent(sessionId, cursor + 1, nextType, nextData, occurredAt, null);
    }

    public String payload() {
        return data;
    }
}
