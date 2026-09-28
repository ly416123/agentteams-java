package io.agentteams.controlplane.conversation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Input for creating one control-plane conversation. */
public record CreateConversationCommand(UUID id, String title, Instant createdAt) {
    public CreateConversationCommand {
        Objects.requireNonNull(id, "id");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
