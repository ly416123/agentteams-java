package io.agentteams.manager.conversation;

/** Verified identity captured when a conversation is created. */
public record ConversationOwner(String tenantId, String subject) {
    public ConversationOwner {
        requireText(tenantId, "tenantId");
        requireText(subject, "subject");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
