package io.agentteams.controlplane.conversation;

import io.agentteams.controlplane.security.ExecutionContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Scoped persistence boundary for conversation message facts. */
public interface ConversationMessageRepository {
    ConversationMessageRecord appendMessage(ExecutionContext context, AppendConversationMessageCommand command);

    Optional<ConversationMessageRecord> findByIdempotency(ExecutionContext context,
            UUID conversationId, String idempotencyKey);

    List<ConversationMessageRecord> messages(ExecutionContext context, UUID conversationId,
            long after, int limit);
}
