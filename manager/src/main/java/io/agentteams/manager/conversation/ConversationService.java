package io.agentteams.manager.conversation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Application service for conversation lifecycle, replay and idempotency. */
public final class ConversationService {
    public enum Status { CREATED, ACTIVE, CANCELLED }

    public record Conversation(UUID sessionId, ConversationRuntimePort.Context context, Status status) {
    }

    public record SendResult(UUID sessionId, String idempotencyKey, List<ConversationEvent> events) {
        public SendResult {
            events = List.copyOf(events);
        }
    }

    private final ConversationRuntimePort runtime;
    private final Map<UUID, SessionState> sessions = new ConcurrentHashMap<>();

    public ConversationService(ConversationRuntimePort runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public Conversation create(ConversationRuntimePort.Context context) {
        Objects.requireNonNull(context, "context");
        SessionState candidate = new SessionState(context);
        SessionState existing = sessions.putIfAbsent(context.sessionId(), candidate);
        if (existing != null && !existing.context.equals(context)) {
            throw new ConversationRuntimeException(ConversationRuntimeException.Code.IDEMPOTENCY_CONFLICT,
                    "conversation session already exists with different context");
        }
        return snapshot(existing == null ? candidate : existing);
    }

    public Conversation createAndStart(ConversationRuntimePort.Context context) {
        Conversation conversation = create(context);
        return start(conversation.sessionId());
    }

    public Conversation start(UUID sessionId) {
        SessionState state = session(sessionId);
        synchronized (state) {
            if (state.status == Status.CANCELLED) {
                return snapshot(state);
            }
            if (state.status == Status.CREATED) {
                runtime.start(state.context);
                state.status = Status.ACTIVE;
            }
            return snapshot(state);
        }
    }

    public SendResult send(UUID sessionId, String idempotencyKey, String content) {
        SessionState state = session(sessionId);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        synchronized (state) {
            SendRecord previous = state.messages.get(idempotencyKey);
            if (previous != null) {
                if (!previous.content.equals(content)) {
                    throw new ConversationRuntimeException(
                            ConversationRuntimeException.Code.IDEMPOTENCY_CONFLICT,
                            "message idempotency key was reused with different content");
                }
                return previous.result;
            }
            if (state.status == Status.CANCELLED) {
                throw new ConversationRuntimeException(ConversationRuntimeException.Code.CANCELLED,
                        "conversation has been cancelled");
            }
            if (state.status != Status.ACTIVE) {
                throw new ConversationRuntimeException(ConversationRuntimeException.Code.INVALID_STATE,
                        "conversation has not started");
            }
            long afterCursor = lastCursor(runtime.events(sessionId, 0));
            runtime.send(new ConversationRuntimePort.Message(sessionId, idempotencyKey, content));
            SendResult result = new SendResult(sessionId, idempotencyKey,
                    runtime.events(sessionId, afterCursor));
            state.messages.put(idempotencyKey, new SendRecord(content, result));
            return result;
        }
    }

    public List<ConversationEvent> events(UUID sessionId, long afterCursor) {
        session(sessionId);
        return runtime.events(sessionId, afterCursor);
    }

    public Conversation cancel(UUID sessionId) {
        return cancel(sessionId, "cancel-" + sessionId);
    }

    public Conversation cancel(UUID sessionId, String idempotencyKey) {
        SessionState state = session(sessionId);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        synchronized (state) {
            if (state.cancelKeys.containsKey(idempotencyKey)) {
                return snapshot(state);
            }
            if (state.status != Status.CANCELLED) {
                runtime.cancel(sessionId);
                state.status = Status.CANCELLED;
            }
            state.cancelKeys.put(idempotencyKey, Boolean.TRUE);
            return snapshot(state);
        }
    }

    private SessionState session(UUID sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            throw ConversationRuntimeException.sessionNotFound();
        }
        return state;
    }

    private static long lastCursor(List<ConversationEvent> events) {
        return events.isEmpty() ? 0 : events.get(events.size() - 1).cursor();
    }

    private static Conversation snapshot(SessionState state) {
        return new Conversation(state.context.sessionId(), state.context, state.status);
    }

    private static final class SessionState {
        private final ConversationRuntimePort.Context context;
        private final Map<String, SendRecord> messages = new ConcurrentHashMap<>();
        private final Map<String, Boolean> cancelKeys = new ConcurrentHashMap<>();
        private Status status = Status.CREATED;

        private SessionState(ConversationRuntimePort.Context context) {
            this.context = context;
        }
    }

    private record SendRecord(String content, SendResult result) {
    }
}
