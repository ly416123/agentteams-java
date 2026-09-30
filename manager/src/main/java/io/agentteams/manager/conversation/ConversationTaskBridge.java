package io.agentteams.manager.conversation;

import io.agentteams.application.api.TaskCommandPort.TaskCreationResult;

/** Optional conversation-to-Control-Plane task hand-off. */
public interface ConversationTaskBridge {
    TaskCreationResult createAndQueue(ConversationRuntimePort.Context context,
            ConversationOwner owner, String content, String idempotencyKey);

    static ConversationTaskBridge noop() {
        return (context, owner, content, idempotencyKey) -> {
            throw new ConversationRuntimeException(ConversationRuntimeException.Code.PROTOCOL_ERROR,
                    "conversation task bridge is not configured");
        };
    }
}
