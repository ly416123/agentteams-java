package io.agentteams.controlplane.conversation;

import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.ExecutionContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Domain operations for scoped conversations, messages, and source tasks. */
@Service
public final class ConversationService {
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final ConversationTaskRepository tasks;

    public ConversationService(ConversationRepository conversations, ConversationMessageRepository messages,
            ConversationTaskRepository tasks) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    public ConversationRecord create(ExecutionContext context, CreateConversationCommand command) {
        ConversationRepository.requireContext(context);
        return conversations.create(context, Objects.requireNonNull(command, "command"));
    }

    public Optional<ConversationRecord> find(ExecutionContext context, UUID conversationId) {
        ConversationRepository.requireContext(context);
        return conversations.find(context, Objects.requireNonNull(conversationId, "conversationId"));
    }

    public ConversationRecord archive(ExecutionContext context, UUID conversationId, long expectedVersion) {
        ConversationRepository.requireContext(context);
        return conversations.archive(context, Objects.requireNonNull(conversationId, "conversationId"), expectedVersion);
    }

    public ConversationMessageRecord appendMessage(ExecutionContext context,
            AppendConversationMessageCommand command) {
        ConversationRepository.requireContext(context);
        Objects.requireNonNull(command, "command");
        requireActiveConversation(context, command.conversationId());
        return messages.appendMessage(context, command);
    }

    public List<ConversationMessageRecord> listMessages(ExecutionContext context, UUID conversationId,
            long after, int limit) {
        ConversationRepository.requireContext(context);
        requireConversation(context, conversationId);
        return messages.messages(context, conversationId, after, limit);
    }

    public TaskRecord bindTask(ExecutionContext context, UUID conversationId, UUID taskId) {
        ConversationRepository.requireContext(context);
        requireConversation(context, conversationId);
        return tasks.bindSourceConversation(context, Objects.requireNonNull(taskId, "taskId"), conversationId);
    }

    private void requireActiveConversation(ExecutionContext context, UUID conversationId) {
        ConversationRecord conversation = requireConversation(context, conversationId);
        if ("ARCHIVED".equals(conversation.status())) {
            throw new IllegalStateException("conversation is archived: " + conversationId);
        }
    }

    private ConversationRecord requireConversation(ExecutionContext context, UUID conversationId) {
        return find(context, Objects.requireNonNull(conversationId, "conversationId"))
                .orElseThrow(() -> new IllegalArgumentException(
                        "conversation is not in the requested scope: " + conversationId));
    }
}
