package io.agentteams.manager.conversation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Manager-side boundary for mirroring legacy conversation facts into Control Plane. */
public interface ControlPlaneConversationPort {

    void create(CreateRequest request);

    void appendMessage(MessageRequest request);

    static ControlPlaneConversationPort noop() {
        return new ControlPlaneConversationPort() {
            @Override
            public void create(CreateRequest request) { }

            @Override
            public void appendMessage(MessageRequest request) { }
        };
    }

    record CreateRequest(UUID conversationId, String tenantId, String projectId, String teamId,
            String title, String actorId, String idempotencyKey, long aggregateVersion, Instant occurredAt) {
        public CreateRequest {
            Objects.requireNonNull(conversationId, "conversationId");
            requireText(tenantId, "tenantId");
            requireText(projectId, "projectId");
            requireText(teamId, "teamId");
            requireText(title, "title");
            requireText(actorId, "actorId");
            requireText(idempotencyKey, "idempotencyKey");
            if (aggregateVersion < 0) throw new IllegalArgumentException("aggregateVersion must not be negative");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    record MessageRequest(UUID conversationId, String tenantId, String projectId, String teamId,
            String actorId, String channel, String messageType, String content, String visibility,
            String idempotencyKey, long aggregateVersion, Instant occurredAt) {
        public MessageRequest {
            Objects.requireNonNull(conversationId, "conversationId");
            requireText(tenantId, "tenantId");
            requireText(projectId, "projectId");
            requireText(teamId, "teamId");
            requireText(actorId, "actorId");
            requireText(channel, "channel");
            requireText(messageType, "messageType");
            requireText(content, "content");
            requireText(visibility, "visibility");
            requireText(idempotencyKey, "idempotencyKey");
            if (aggregateVersion < 0) throw new IllegalArgumentException("aggregateVersion must not be negative");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
