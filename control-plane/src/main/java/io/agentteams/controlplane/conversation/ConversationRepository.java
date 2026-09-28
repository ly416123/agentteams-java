package io.agentteams.controlplane.conversation;

import io.agentteams.controlplane.security.ExecutionContext;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Scoped persistence boundary for control-plane conversations. */
public interface ConversationRepository {
    ConversationRecord create(ExecutionContext context, CreateConversationCommand command);

    Optional<ConversationRecord> find(ExecutionContext context, UUID id);

    ConversationRecord archive(ExecutionContext context, UUID id, long expectedVersion);

    static void requireContext(ExecutionContext context) {
        Objects.requireNonNull(context, "context");
    }
}
