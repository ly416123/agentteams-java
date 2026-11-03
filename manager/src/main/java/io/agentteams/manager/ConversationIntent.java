package io.agentteams.manager;

import java.util.Objects;

/** Model-routed outcome for one conversation message. */
public record ConversationIntent(Type type, String response, CreateTaskIntent task) {
    public enum Type { CHAT, CREATE_TASK, CLARIFY }

    public ConversationIntent {
        Objects.requireNonNull(type, "type");
        if (type == Type.CREATE_TASK) {
            Objects.requireNonNull(task, "task");
            if (response != null && response.isBlank()) response = null;
        } else {
            if (response == null || response.isBlank()) {
                throw new IllegalArgumentException("response is required for chat and clarify intents");
            }
            if (task != null) throw new IllegalArgumentException("chat and clarify intents cannot contain a task");
        }
    }

    public static ConversationIntent chat(String response) {
        return new ConversationIntent(Type.CHAT, response, null);
    }

    public static ConversationIntent clarify(String response) {
        return new ConversationIntent(Type.CLARIFY, response, null);
    }

    public static ConversationIntent createTask(CreateTaskIntent task) {
        return new ConversationIntent(Type.CREATE_TASK, null, task);
    }
}
