package io.agentteams.manager.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Deterministic in-memory runtime for local development and tests. */
public final class FakeConversationRuntime implements ConversationRuntimePort {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final Map<UUID, State> sessions = new ConcurrentHashMap<>();
    private final boolean workerAvailable;

    public FakeConversationRuntime() {
        this(true);
    }

    public FakeConversationRuntime(boolean workerAvailable) {
        this.workerAvailable = workerAvailable;
    }

    @Override
    public void start(Context context) {
        if (!workerAvailable) {
            throw ConversationRuntimeException.workerUnavailable();
        }
        State state = sessions.computeIfAbsent(context.sessionId(), ignored -> new State(context));
        synchronized (state) {
            if (state.cancelled) {
                return;
            }
            if (!state.started) {
                state.started = true;
                state.append("conversation.started", "{}");
            }
        }
    }

    @Override
    public void send(Message message) {
        State state = session(message.sessionId());
        synchronized (state) {
            if (!state.started) {
                throw new ConversationRuntimeException(ConversationRuntimeException.Code.INVALID_STATE,
                        "conversation has not started");
            }
            if (state.cancelled) {
                return;
            }
            String text = "FAKE: " + message.content();
            state.append("message.delta", jsonText(text));
            state.append("message.completed", jsonText(text));
        }
    }

    @Override
    public List<ConversationEvent> events(UUID sessionId, long afterCursor) {
        if (afterCursor < 0) {
            throw new IllegalArgumentException("afterCursor must not be negative");
        }
        State state = sessions.get(sessionId);
        if (state == null) {
            return List.of();
        }
        synchronized (state) {
            return state.events.stream()
                    .filter(event -> event.cursor() > afterCursor)
                    .toList();
        }
    }

    @Override
    public void cancel(UUID sessionId) {
        State state = session(sessionId);
        synchronized (state) {
            if (!state.cancelled) {
                state.cancelled = true;
                state.append("conversation.cancelled", "{}");
            }
        }
    }

    private State session(UUID sessionId) {
        State state = sessions.get(sessionId);
        if (state == null) {
            throw ConversationRuntimeException.sessionNotFound();
        }
        return state;
    }

    private static String jsonText(String value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of("text", value));
        } catch (JsonProcessingException error) {
            throw new ConversationRuntimeException(ConversationRuntimeException.Code.PROTOCOL_ERROR,
                    "unable to encode fake conversation event", error);
        }
    }

    private static final class State {
        private final Context context;
        private final List<ConversationEvent> events = new ArrayList<>();
        private boolean started;
        private boolean cancelled;

        private State(Context context) {
            this.context = context;
        }

        private void append(String type, String data) {
            events.add(ConversationEvent.of(context.sessionId(), events.size() + 1, type, data, Instant.EPOCH));
        }
    }
}
