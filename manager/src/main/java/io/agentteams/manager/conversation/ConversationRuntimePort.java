package io.agentteams.manager.conversation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Runtime boundary for conversation execution. */
public interface ConversationRuntimePort extends AutoCloseable {
    void start(Context context);

    void send(Message message);

    List<ConversationEvent> events(UUID sessionId, long afterCursor);

    void cancel(UUID sessionId);

    @Override
    default void close() {
        // In-memory runtimes do not own external resources.
    }

    record Context(String project, String team, String worker, String task, UUID sessionId) {
        public Context {
            requireText(project, "project");
            requireText(team, "team");
            if (worker != null && worker.isBlank()) {
                throw new IllegalArgumentException("worker must not be blank when supplied");
            }
            if (task != null && task.isBlank()) {
                throw new IllegalArgumentException("task must not be blank when supplied");
            }
            Objects.requireNonNull(sessionId, "sessionId");
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }

    record Message(UUID sessionId, String idempotencyKey, String content) {
        public Message {
            Objects.requireNonNull(sessionId, "sessionId");
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("idempotencyKey must not be blank");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("content must not be blank");
            }
        }
    }
}
