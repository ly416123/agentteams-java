package io.agentteams.controlplane.conversation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable message fact stored separately from task events. */
public record ConversationMessageRecord(UUID id, UUID conversationId, String tenantId,
        String senderType, String senderId, String channel, String messageType,
        String content, String contentRef, String visibility, Instant occurredAt,
        long sequence, String idempotencyKey, String traceId) {
    public ConversationMessageRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(conversationId, "conversationId");
        requireText(tenantId, "tenantId");
        requireText(senderType, "senderType");
        requireText(senderId, "senderId");
        requireText(channel, "channel");
        requireText(messageType, "messageType");
        if ((content == null) == (contentRef == null)) {
            throw new IllegalArgumentException("exactly one of content and contentRef must be supplied");
        }
        if (content != null) requireText(content, "content");
        if (contentRef != null) requireText(contentRef, "contentRef");
        if (!isVisibility(visibility)) {
            throw new IllegalArgumentException("visibility must be PUBLIC, TEAM, PRIVATE, or INTERNAL");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        requireText(traceId, "traceId");
    }

    private static boolean isVisibility(String value) {
        return "PUBLIC".equals(value) || "TEAM".equals(value)
                || "PRIVATE".equals(value) || "INTERNAL".equals(value);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
