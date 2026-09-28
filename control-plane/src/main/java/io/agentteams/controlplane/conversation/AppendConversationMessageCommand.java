package io.agentteams.controlplane.conversation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Input for appending one conversation message fact. */
public record AppendConversationMessageCommand(UUID id, UUID conversationId, String senderType,
        String senderId, String channel, String messageType, String content, String contentRef,
        String visibility, Instant occurredAt, String idempotencyKey, String traceId) {
    public AppendConversationMessageCommand {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(conversationId, "conversationId");
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
        if (idempotencyKey != null && idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank when supplied");
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
