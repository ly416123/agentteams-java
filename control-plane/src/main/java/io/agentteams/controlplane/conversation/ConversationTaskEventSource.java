package io.agentteams.controlplane.conversation;

import io.agentteams.application.api.TimelineItem;
import io.agentteams.controlplane.security.ExecutionContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Scoped source for task facts projected into a conversation timeline. */
@FunctionalInterface
public interface ConversationTaskEventSource {
    List<TimelineItem> list(ExecutionContext context, UUID conversationId, Set<String> visibility,
            long after, int limit);
}
