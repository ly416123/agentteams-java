package io.agentteams.controlplane.conversation;

import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.ExecutionContext;
import java.util.UUID;

/** Scoped persistence boundary for assigning a task to one source conversation. */
public interface ConversationTaskRepository {
    TaskRecord bindSourceConversation(ExecutionContext context, UUID taskId, UUID conversationId);
}
