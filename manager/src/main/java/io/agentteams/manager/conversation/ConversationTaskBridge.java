package io.agentteams.manager.conversation;

import io.agentteams.application.api.TaskCommandPort.TaskCreationResult;
import io.agentteams.manager.CreateTaskIntent;

/** Optional conversation-to-Control-Plane task hand-off. */
public interface ConversationTaskBridge {
    TaskCreationResult createAndQueue(ConversationRuntimePort.Context context,
            ConversationOwner owner, CreateTaskIntent intent, String idempotencyKey);

    static ConversationTaskBridge noop() {
        return (context, owner, intent, idempotencyKey) -> {
            throw new ConversationRuntimeException(ConversationRuntimeException.Code.PROTOCOL_ERROR,
                    "conversation task bridge is not configured");
        };
    }
}
