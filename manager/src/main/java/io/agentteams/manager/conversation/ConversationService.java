package io.agentteams.manager.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import io.agentteams.manager.security.ManagerAuthorizationException;

/** Application service for conversation lifecycle, durable replay and idempotency. */
public final class ConversationService {
    public enum Status { CREATED, ACTIVE, CANCELLING, CANCELLED }

    public record Conversation(UUID sessionId, ConversationRuntimePort.Context context, Status status,
            ConversationOwner owner, long version) {
        public Conversation(UUID sessionId, ConversationRuntimePort.Context context, Status status) {
            this(sessionId, context, status, null, 0);
        }
    }

    public record SendResult(UUID sessionId, String idempotencyKey, List<ConversationEvent> events) {
        public SendResult {
            events = List.copyOf(events);
        }
    }

    public record History(List<ConversationRepository.MessageRecord> messages, List<ConversationEvent> events) {
        public History {
            messages = List.copyOf(messages);
            events = List.copyOf(events);
        }
    }

    private final ConversationRuntimePort runtime;
    private final ConversationRepository repository;
    private final Map<UUID, SessionState> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService eventSynchronizer = Executors.newScheduledThreadPool(2,
            daemonThreadFactory());

    public ConversationService(ConversationRuntimePort runtime) {
        this(runtime, new InMemoryConversationRepository());
    }

    public ConversationService(ConversationRuntimePort runtime, ConversationRepository repository) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Conversation create(ConversationRuntimePort.Context context) {
        return create(context, null, null);
    }

    public Conversation create(ConversationRuntimePort.Context context, String idempotencyKey) {
        return create(context, idempotencyKey, null);
    }

    public Conversation create(ConversationRuntimePort.Context context, String idempotencyKey,
            ConversationOwner owner) {
        Objects.requireNonNull(context, "context");
        ConversationRepository.ConversationRecord record = repository.saveSession(
                context, Status.CREATED, Instant.now(), idempotencyKey, owner);
        SessionState state = sessions.computeIfAbsent(record.context().sessionId(), ignored -> new SessionState(record));
        if (!state.context.equals(context)) {
            throw new ConversationRuntimeException(ConversationRuntimeException.Code.IDEMPOTENCY_CONFLICT,
                    "conversation session already exists with different context");
        }
        if (owner != null && state.owner != null && !owner.equals(state.owner)) {
            throw new ConversationRuntimeException(ConversationRuntimeException.Code.IDEMPOTENCY_CONFLICT,
                    "conversation session already exists with different owner");
        }
        return snapshot(state);
    }

    public Conversation createAndStart(ConversationRuntimePort.Context context) {
        Conversation conversation = create(context, null);
        return start(conversation.sessionId());
    }

    public Conversation createAndStart(ConversationRuntimePort.Context context, String idempotencyKey) {
        return createAndStart(context, idempotencyKey, null);
    }

    public Conversation createAndStart(ConversationRuntimePort.Context context, String idempotencyKey,
            ConversationOwner owner) {
        Conversation conversation = create(context, idempotencyKey, owner);
        return start(conversation.sessionId());
    }

    public Conversation get(UUID sessionId) {
        return snapshot(session(sessionId));
    }

    public Conversation get(UUID sessionId, ConversationOwner caller) {
        SessionState state = session(sessionId);
        authorize(state, caller);
        return snapshot(state);
    }

    public History history(UUID sessionId) {
        SessionState state = session(sessionId);
        synchronized (state) {
            syncRuntimeEvents(state);
            freezeTerminalCursors(state, repository.findEvents(sessionId));
            return new History(repository.findMessages(sessionId), repository.findEvents(sessionId));
        }
    }

    public History history(UUID sessionId, ConversationOwner caller) {
        SessionState state = session(sessionId);
        authorize(state, caller);
        return history(sessionId);
    }

    public Conversation start(UUID sessionId) {
        SessionState state = session(sessionId);
        synchronized (state) {
            if (state.status == Status.CANCELLED) return snapshot(state);
            if (!state.runtimeStarted) {
                runtime.start(state.context);
                state.runtimeStarted = true;
                reconcileRuntimeAfterStart(state);
            }
            if (state.status == Status.CREATED) {
                ConversationRepository.ConversationRecord updated = repository.updateStatus(sessionId, Status.ACTIVE,
                        Instant.now(), state.version);
                state.status = updated.status();
                state.version = updated.version();
            }
            return snapshot(state);
        }
    }

    public SendResult send(UUID sessionId, String idempotencyKey, String content) {
        return send(sessionId, idempotencyKey, content, null);
    }

    public SendResult send(UUID sessionId, String idempotencyKey, String content, Long expectedVersion) {
        return send(sessionId, idempotencyKey, content, expectedVersion, null);
    }

    public SendResult send(UUID sessionId, String idempotencyKey, String content, Long expectedVersion,
            ConversationOwner caller) {
        SessionState state = session(sessionId);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        synchronized (state) {
            authorize(state, caller);
            if (state.status == Status.ACTIVE && !state.runtimeStarted) {
                runtime.start(state.context);
                state.runtimeStarted = true;
                reconcileRuntimeAfterStart(state);
            }
            syncRuntimeEvents(state);
            SendRecord previous = state.messages.get(idempotencyKey);
            if (previous == null) {
                previous = repository.findMessage(sessionId, idempotencyKey)
                        .map(SendRecord::from).orElse(null);
                if (previous != null) state.messages.put(idempotencyKey, previous);
            }
            if (previous != null) {
                if (!previous.content.equals(content)) {
                    throw new ConversationRuntimeException(
                            ConversationRuntimeException.Code.IDEMPOTENCY_CONFLICT,
                            "message idempotency key was reused with different content");
                }
                if (previous.status == ConversationRepository.MessageStatus.RECOVERY_REQUIRED) {
                    throw new ConversationRuntimeException(ConversationRuntimeException.Code.RECOVERY_REQUIRED,
                            "conversation message requires operator recovery before retry");
                }
                List<ConversationEvent> replay = previous.responseCaptured && !previous.responseEvents.isEmpty()
                        ? previous.responseEvents : eventsFor(previous, state, repository.findEvents(sessionId));
                if (!replay.isEmpty()) {
                    previous.responseEvents = replay;
                    previous.responseCaptured = true;
                }
                return new SendResult(sessionId, idempotencyKey, replay);
            }
            if (state.status == Status.CANCELLED) {
                throw new ConversationRuntimeException(ConversationRuntimeException.Code.CANCELLED,
                        "conversation has been cancelled");
            }
            if (state.status == Status.CANCELLING) {
                throw new ConversationRuntimeException(ConversationRuntimeException.Code.INVALID_STATE,
                        "conversation cancellation is in progress");
            }
            if (state.status != Status.ACTIVE) {
                throw new ConversationRuntimeException(ConversationRuntimeException.Code.INVALID_STATE,
                        "conversation has not started");
            }
            if (state.messages.values().stream()
                    .anyMatch(record -> record.status == ConversationRepository.MessageStatus.RECOVERY_REQUIRED)) {
                throw new ConversationRuntimeException(ConversationRuntimeException.Code.RECOVERY_REQUIRED,
                        "conversation has a message requiring operator recovery");
            }
            requireVersion(sessionId, state, expectedVersion);
            long startCursor = lastCursor(repository.findEvents(sessionId));
            ConversationRepository.MessageRecord requested = new ConversationRepository.MessageRecord(
                    sessionId, idempotencyKey, content, startCursor, null, Instant.now(),
                    ConversationRepository.MessageStatus.RESERVED);
            ConversationRepository.MessageReservation reservation = repository.reserveMessage(sessionId, state.version,
                    requested);
            if (!reservation.message().content().equals(content)) {
                throw new ConversationRuntimeException(ConversationRuntimeException.Code.IDEMPOTENCY_CONFLICT,
                        "message idempotency key was reused with different content");
            }
            ConversationRepository.ConversationRecord reservedSession = reservation.session();
            state.status = reservedSession.status();
            state.version = reservedSession.version();
            SendRecord record = SendRecord.from(reservation.message());
            state.messages.put(idempotencyKey, record);
            if (!reservation.acquired()) {
                if (record.status == ConversationRepository.MessageStatus.RECOVERY_REQUIRED) {
                    throw new ConversationRuntimeException(ConversationRuntimeException.Code.RECOVERY_REQUIRED,
                            "conversation message requires operator recovery before retry");
                }
                List<ConversationEvent> replay = eventsFor(record, state, repository.findEvents(sessionId));
                record.responseEvents = replay;
                record.responseCaptured = !replay.isEmpty();
                return new SendResult(sessionId, idempotencyKey, replay);
            }
            try {
                runtime.send(new ConversationRuntimePort.Message(sessionId, idempotencyKey, content));
            } catch (RuntimeException error) {
                repository.updateMessageStatus(sessionId, idempotencyKey,
                        ConversationRepository.MessageStatus.RECOVERY_REQUIRED);
                record.status = ConversationRepository.MessageStatus.RECOVERY_REQUIRED;
                throw error;
            }
            syncRuntimeEvents(state);
            List<ConversationEvent> produced = repository.findEvents(sessionId, startCursor);
            freezeTerminalCursors(state, produced);
            scheduleEventPersistence(state);
            record.responseEvents = eventsFor(record, state, produced);
            record.responseCaptured = true;
            if (record.endCursor != null) repository.updateMessageEnd(sessionId, idempotencyKey, record.endCursor);
            return new SendResult(sessionId, idempotencyKey, record.responseEvents);
        }
    }

    public List<ConversationEvent> events(UUID sessionId, long afterCursor) {
        return events(sessionId, afterCursor, null);
    }

    public List<ConversationEvent> events(UUID sessionId, long afterCursor, ConversationOwner caller) {
        if (afterCursor < 0) throw new IllegalArgumentException("afterCursor must not be negative");
        SessionState state = sessionForEventRead(sessionId);
        synchronized (state) {
            authorize(state, caller);
            syncRuntimeEvents(state);
            freezeTerminalCursors(state, repository.findEvents(sessionId));
            return repository.findEvents(sessionId, afterCursor);
        }
    }

    public boolean hasPendingMessage(UUID sessionId) {
        return hasPendingMessage(sessionId, null);
    }

    public boolean hasPendingMessage(UUID sessionId, ConversationOwner caller) {
        SessionState state = sessionForEventRead(sessionId);
        synchronized (state) {
            authorize(state, caller);
            syncRuntimeEvents(state);
            freezeTerminalCursors(state, repository.findEvents(sessionId));
            return state.messages.values().stream()
                    .anyMatch(record -> record.status == ConversationRepository.MessageStatus.RESERVED);
        }
    }

    public Conversation cancel(UUID sessionId) {
        return cancel(sessionId, "cancel-" + sessionId, null);
    }

    public Conversation cancel(UUID sessionId, String idempotencyKey) {
        return cancel(sessionId, idempotencyKey, null);
    }

    public Conversation cancel(UUID sessionId, String idempotencyKey, Long expectedVersion) {
        return cancel(sessionId, idempotencyKey, expectedVersion, null);
    }

    public Conversation cancel(UUID sessionId, String idempotencyKey, Long expectedVersion,
            ConversationOwner caller) {
        SessionState state = session(sessionId);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        synchronized (state) {
            authorize(state, caller);
            if (state.cancelKeys.containsKey(idempotencyKey)) return snapshot(state);
            if (state.status != Status.CANCELLED) {
                try {
                    requireVersion(sessionId, state, expectedVersion);
                    if (!state.runtimeStarted && state.status == Status.ACTIVE) {
                        runtime.start(state.context);
                        state.runtimeStarted = true;
                        reconcileRuntimeAfterStart(state);
                    }
                    runtime.cancel(sessionId);
                    syncRuntimeEvents(state);
                    freezeTerminalCursors(state, repository.findEvents(sessionId));
                    ConversationRepository.ConversationRecord updated = repository.updateStatus(sessionId,
                            Status.CANCELLED, Instant.now(), state.version);
                    state.status = updated.status();
                    state.version = updated.version();
                } catch (RuntimeException error) {
                    state.status = Status.ACTIVE;
                    throw error;
                }
            }
            state.cancelKeys.put(idempotencyKey, Boolean.TRUE);
            return snapshot(state);
        }
    }

    /** Stop background persistence when the Manager application is shutting down. */
    public void close() {
        eventSynchronizer.shutdownNow();
    }

    private void scheduleEventPersistence(SessionState state) {
        if (state.messages.values().stream()
                .noneMatch(record -> record.status == ConversationRepository.MessageStatus.RESERVED)) {
            return;
        }
        ScheduledFuture<?>[] task = new ScheduledFuture<?>[1];
        task[0] = eventSynchronizer.scheduleWithFixedDelay(() -> {
            boolean complete;
            synchronized (state) {
                if (state.status == Status.CANCELLED) {
                    complete = true;
                } else {
                    syncRuntimeEvents(state);
                    freezeTerminalCursors(state, repository.findEvents(state.context.sessionId()));
                    refreshMessages(state);
                    complete = state.messages.values().stream()
                            .noneMatch(record -> record.status == ConversationRepository.MessageStatus.RESERVED);
                }
            }
            if (complete) task[0].cancel(false);
        }, 0, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "conversation-event-persistence");
            thread.setDaemon(true);
            return thread;
        };
    }

    private SessionState session(UUID sessionId) {
        return session(sessionId, true);
    }

    private SessionState sessionForEventRead(UUID sessionId) {
        return session(sessionId, false);
    }

    private SessionState session(UUID sessionId, boolean recoverReserved) {
        SessionState state = sessions.get(sessionId);
        if (state != null) {
            refreshMessages(state);
            return state;
        }
        ConversationRepository.ConversationRecord record = repository.findSession(sessionId)
                .orElseThrow(ConversationRuntimeException::sessionNotFound);
        SessionState loaded = new SessionState(record);
        refreshMessages(loaded);
        if (recoverReserved) {
            markReservedMessagesForRecovery(loaded);
        }
        SessionState existing = sessions.putIfAbsent(sessionId, loaded);
        if (existing != null) {
            refreshMessages(existing);
            return existing;
        }
        return loaded;
    }

    private void refreshMessages(SessionState state) {
        repository.findMessages(state.context.sessionId()).forEach(message -> {
            SendRecord existing = state.messages.get(message.idempotencyKey());
            if (existing == null) {
                state.messages.put(message.idempotencyKey(), SendRecord.from(message));
            } else {
                existing.endCursor = message.endCursor();
                existing.status = message.status();
            }
        });
    }

    private void markReservedMessagesForRecovery(SessionState state) {
        for (SendRecord message : state.messages.values()) {
            if (message.status == ConversationRepository.MessageStatus.RESERVED) {
                repository.updateMessageStatus(state.context.sessionId(), message.idempotencyKey,
                        ConversationRepository.MessageStatus.RECOVERY_REQUIRED);
                message.status = ConversationRepository.MessageStatus.RECOVERY_REQUIRED;
                repository.appendEvent(state.context.sessionId(), ConversationEvent.of(state.context.sessionId(), 1,
                        "conversation.recovery_required", "{\"code\":\"IN_FLIGHT_RECOVERY_REQUIRED\"}",
                        Instant.now()));
            }
        }
    }

    private void syncRuntimeEvents(SessionState state) {
        if (!state.runtimeStarted || state.status == Status.CANCELLED) return;
        for (ConversationEvent event : runtime.events(state.context.sessionId(), state.runtimeLastCursor)) {
            ConversationEvent stored = repository.appendEvent(state.context.sessionId(), event);
            state.runtimeLastCursor = Math.max(state.runtimeLastCursor, event.cursor());
            state.lastPersistentCursor = Math.max(state.lastPersistentCursor, stored.cursor());
        }
    }

    private void reconcileRuntimeAfterStart(SessionState state) {
        List<ConversationEvent> runtimeEvents = runtime.events(state.context.sessionId(), 0);
        List<ConversationEvent> persisted = repository.findEvents(state.context.sessionId());
        int prefix = 0;
        while (prefix < runtimeEvents.size() && prefix < persisted.size()
                && sameEvent(runtimeEvents.get(prefix), persisted.get(prefix))) {
            prefix++;
        }
        state.runtimeLastCursor = prefix == 0 ? 0 : runtimeEvents.get(prefix - 1).cursor();
        state.lastPersistentCursor = lastCursor(persisted);
        for (int index = prefix; index < runtimeEvents.size(); index++) {
            ConversationEvent stored = repository.appendEvent(state.context.sessionId(), runtimeEvents.get(index));
            state.runtimeLastCursor = runtimeEvents.get(index).cursor();
            state.lastPersistentCursor = stored.cursor();
        }
    }

    private static boolean sameEvent(ConversationEvent left, ConversationEvent right) {
        return left.type().equals(right.type()) && left.data().equals(right.data())
                && java.util.Objects.equals(left.sourceEventId(), right.sourceEventId());
    }

    private static long lastCursor(List<ConversationEvent> events) {
        return events.isEmpty() ? 0 : events.get(events.size() - 1).cursor();
    }

    private void freezeTerminalCursors(SessionState state, List<ConversationEvent> observed) {
        for (SendRecord record : state.messages.values()) {
            if (record.endCursor != null) continue;
            observed.stream()
                    .filter(event -> event.cursor() > record.startCursor)
                    .filter(event -> isTerminal(event.type()))
                    .mapToLong(ConversationEvent::cursor)
                    .min()
            .ifPresent(cursor -> {
                record.freezeAt(cursor);
                repository.updateMessageEnd(state.context.sessionId(), record.idempotencyKey, cursor);
                if (observed.stream().anyMatch(event -> event.cursor() == cursor
                        && "conversation.failed".equals(event.type()))) {
                    record.status = ConversationRepository.MessageStatus.FAILED;
                    repository.updateMessageStatus(state.context.sessionId(), record.idempotencyKey,
                            ConversationRepository.MessageStatus.FAILED);
                }
            });
        }
    }

    private static List<ConversationEvent> eventsFor(SendRecord record, SessionState state,
            List<ConversationEvent> observed) {
        long endCursor = lastCursor(observed);
        if (record.endCursor != null) endCursor = Math.min(endCursor, record.endCursor);
        long nextStartCursor = state.messages.values().stream()
                .filter(candidate -> candidate.startCursor > record.startCursor)
                .mapToLong(candidate -> candidate.startCursor)
                .min().orElse(Long.MAX_VALUE);
        if (nextStartCursor != Long.MAX_VALUE) endCursor = Math.min(endCursor, nextStartCursor);
        long upperBound = endCursor;
        return observed.stream().filter(event -> event.cursor() > record.startCursor && event.cursor() <= upperBound)
                .toList();
    }

    private static boolean isTerminal(String type) {
        return "message.completed".equals(type) || "conversation.failed".equals(type)
                || "conversation.cancelled".equals(type);
    }

    private static Conversation snapshot(SessionState state) {
        return new Conversation(state.context.sessionId(), state.context, state.status, state.owner, state.version);
    }

    private static void requireVersion(UUID sessionId, SessionState state, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion.longValue() != state.version) {
            throw new ConversationVersionConflictException(sessionId, expectedVersion, state.version);
        }
    }

    private static void authorize(SessionState state, ConversationOwner caller) {
        if (caller == null || state.owner == null || !caller.equals(state.owner)) {
            if (caller != null) {
                throw new ManagerAuthorizationException("conversation owner does not match authenticated principal");
            }
        }
    }

    private static final class SessionState {
        private final ConversationRuntimePort.Context context;
        private final ConversationOwner owner;
        private final Map<String, SendRecord> messages = new ConcurrentHashMap<>();
        private final Map<String, Boolean> cancelKeys = new ConcurrentHashMap<>();
        private Status status;
        private long version;
        private boolean runtimeStarted;
        private long runtimeLastCursor;
        private long lastPersistentCursor;

        private SessionState(ConversationRepository.ConversationRecord record) {
            this.context = record.context();
            this.owner = record.owner();
            this.status = record.status();
            this.version = record.version();
        }
    }

    private static final class SendRecord {
        private final String idempotencyKey;
        private final String content;
        private final long startCursor;
        private List<ConversationEvent> responseEvents = List.of();
        private boolean responseCaptured;
        private Long endCursor;
        private ConversationRepository.MessageStatus status;

        private SendRecord(String idempotencyKey, String content, long startCursor, Long endCursor,
                ConversationRepository.MessageStatus status) {
            this.idempotencyKey = idempotencyKey;
            this.content = content;
            this.startCursor = startCursor;
            this.endCursor = endCursor;
            this.status = status;
        }

        private static SendRecord from(ConversationRepository.MessageRecord record) {
            return new SendRecord(record.idempotencyKey(), record.content(), record.startCursor(), record.endCursor(),
                    record.status());
        }

        private void freezeAt(long cursor) {
            endCursor = cursor;
        }
    }
}
