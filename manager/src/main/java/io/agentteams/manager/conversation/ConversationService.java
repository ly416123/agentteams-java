package io.agentteams.manager.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import io.agentteams.manager.security.ManagerAuthorizationException;
import io.agentteams.application.api.TaskCommandPort.TaskCreationResult;
import io.agentteams.manager.ConversationIntent;
import io.agentteams.manager.CreateTaskIntent;

/** Application service for conversation lifecycle, durable replay and idempotency. */
public final class ConversationService {
    private static final ObjectMapper JSON = new ObjectMapper();
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

    public record ConversationSummary(UUID sessionId, ConversationRuntimePort.Context context, Status status,
            long version, Instant createdAt, Instant updatedAt, String lastMessage) {
    }

    public record ConversationPage(List<ConversationSummary> items, String nextCursor, boolean hasMore,
            Instant serverTime) {
        public ConversationPage {
            items = List.copyOf(items);
        }
    }

    private final ConversationRuntimePort runtime;
    private final ConversationRepository repository;
    private final ControlPlaneConversationPort controlPlane;
    private final ConversationTaskBridge taskBridge;
    private final ConversationIntentRouter intentRouter;
    private final boolean taskExecution;
    private final Map<UUID, SessionState> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService eventSynchronizer = Executors.newScheduledThreadPool(2,
            daemonThreadFactory());

    public ConversationService(ConversationRuntimePort runtime) {
        this(runtime, new InMemoryConversationRepository(), ControlPlaneConversationPort.noop());
    }

    public ConversationService(ConversationRuntimePort runtime, ConversationRepository repository) {
        this(runtime, repository, ControlPlaneConversationPort.noop());
    }

    public ConversationService(ConversationRuntimePort runtime, ConversationRepository repository,
            ControlPlaneConversationPort controlPlane) {
        this(runtime, repository, controlPlane, ConversationTaskBridge.noop(),
                ConversationIntentRouter.failClosed(), false);
    }

    public ConversationService(ConversationRuntimePort runtime, ConversationRepository repository,
            ControlPlaneConversationPort controlPlane, ConversationTaskBridge taskBridge) {
        this(runtime, repository, controlPlane, taskBridge, ConversationIntentRouter.failClosed(), true);
    }

    public ConversationService(ConversationRuntimePort runtime, ConversationRepository repository,
            ControlPlaneConversationPort controlPlane, ConversationTaskBridge taskBridge,
            ConversationIntentRouter intentRouter) {
        this(runtime, repository, controlPlane, taskBridge, intentRouter, true);
    }

    private ConversationService(ConversationRuntimePort runtime, ConversationRepository repository,
            ControlPlaneConversationPort controlPlane, ConversationTaskBridge taskBridge,
            ConversationIntentRouter intentRouter, boolean taskExecution) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.taskBridge = Objects.requireNonNull(taskBridge, "taskBridge");
        this.intentRouter = Objects.requireNonNull(intentRouter, "intentRouter");
        this.taskExecution = taskExecution;
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
        // Task-backed conversations own their lifecycle in Manager and create the
        // Control Plane task on the first message. Replaying the legacy
        // conversation command here would duplicate the source record and also
        // force Manager's legacy tenant key through the canonical CP context.
        if (!taskExecution && owner != null && idempotencyKey != null && !state.controlPlaneCreated) {
            controlPlane.create(new ControlPlaneConversationPort.CreateRequest(
                    context.sessionId(), owner.tenantId(), context.project(), context.team(),
                    "Manager conversation " + context.sessionId(), owner.subject(), idempotencyKey,
                    state.version, record.createdAt()));
            state.controlPlaneCreated = true;
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

    public ConversationPage list(String projectId, Integer requestedPageSize, String cursor,
            ConversationOwner owner) {
        if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId is required");
        int pageSize = requestedPageSize == null ? 50 : requestedPageSize;
        if (pageSize < 1 || pageSize > 200) {
            throw new IllegalArgumentException("pageSize must be between 1 and 200");
        }
        Objects.requireNonNull(owner, "owner");
        CursorPosition position = decodeCursor(cursor);
        List<ConversationRepository.ConversationRecord> records = repository.findSessions(owner.tenantId(), projectId,
                owner.subject(), position.updatedAt(), position.id(), pageSize + 1);
        boolean hasMore = records.size() > pageSize;
        List<ConversationRepository.ConversationRecord> page = hasMore ? records.subList(0, pageSize) : records;
        List<ConversationSummary> items = page.stream().map(record -> new ConversationSummary(
                record.context().sessionId(), record.context(), record.status(), record.version(), record.createdAt(),
                record.updatedAt(), lastMessage(record.context().sessionId()))).toList();
        String nextCursor = hasMore ? encodeCursor(page.get(page.size() - 1)) : null;
        return new ConversationPage(items, nextCursor, hasMore, Instant.now());
    }

    public Conversation start(UUID sessionId) {
        SessionState state = session(sessionId);
        synchronized (state) {
            if (state.status == Status.CANCELLED) return snapshot(state);
            if (!state.runtimeStarted && !taskExecution) {
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
            if (state.status == Status.ACTIVE && !state.runtimeStarted && !taskExecution) {
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
                bridgeMessage(state, previous.content, idempotencyKey, previous.createdAt, state.version);
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
            long managerVersion = state.version;
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
            if (taskExecution) {
                if (state.owner == null) {
                    throw new ConversationRuntimeException(ConversationRuntimeException.Code.INVALID_STATE,
                            "task-backed conversation requires an owner");
                }
                dispatchRoutedMessage(state, record, content, idempotencyKey);
            } else {
                bridgeMessage(state, content, idempotencyKey, requested.createdAt(), managerVersion);
                dispatchPendingMessages(state);
                syncRuntimeEvents(state);
            }
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
            dispatchPendingMessages(state);
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
            dispatchPendingMessages(state);
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
                    if (!state.runtimeStarted && state.status == Status.ACTIVE && !taskExecution) {
                    runtime.start(state.context);
                        state.runtimeStarted = true;
                        reconcileRuntimeAfterStart(state);
                    }
                    if (!taskExecution) runtime.cancel(sessionId);
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
                    dispatchPendingMessages(state);
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
            java.util.Optional<ConversationEvent> terminal = observed.stream()
                    .filter(event -> record.dispatched)
                    .filter(event -> event.cursor() > record.attemptStartCursor)
                    .filter(event -> isTerminal(event.type()))
                    .min(java.util.Comparator.comparingLong(ConversationEvent::cursor));
            terminal.ifPresent(event -> {
                if (isRetryableFailure(event) && record.retryCount < MAX_AUTOMATIC_RETRIES) {
                    record.retryCount++;
                    record.dispatched = false;
                    record.attemptStartCursor = event.cursor();
                    record.status = ConversationRepository.MessageStatus.RESERVED;
                    repository.updateMessageStatus(state.context.sessionId(), record.idempotencyKey,
                            ConversationRepository.MessageStatus.RESERVED);
                    return;
                }
                long cursor = event.cursor();
                record.freezeAt(cursor);
                repository.updateMessageEnd(state.context.sessionId(), record.idempotencyKey, cursor);
                if ("conversation.failed".equals(event.type())) {
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
                .filter(candidate -> candidate.attemptStartCursor > record.attemptStartCursor)
                .mapToLong(candidate -> candidate.attemptStartCursor)
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

    private static boolean isRetryableFailure(ConversationEvent event) {
        if (!"conversation.failed".equals(event.type())) return false;
        return event.data().contains("CONNECTION_CLOSED") || event.data().contains("TIMEOUT")
                || event.data().contains("WORKER_UNAVAILABLE")
                || event.data().contains("MODEL_PROVIDER_UNAVAILABLE");
    }

    private static final int MAX_AUTOMATIC_RETRIES = 2;

    /** Sends only the oldest undispatched message when no earlier message is active. */
    private void dispatchPendingMessages(SessionState state) {
        refreshMessages(state);
        boolean active = state.messages.values().stream()
                .anyMatch(record -> record.dispatched && record.status == ConversationRepository.MessageStatus.RESERVED);
        if (active) return;
        SendRecord next = state.messages.values().stream()
                .filter(record -> !record.dispatched && record.status == ConversationRepository.MessageStatus.RESERVED)
                .min(java.util.Comparator.comparing((SendRecord record) -> record.createdAt)
                        .thenComparing(record -> record.idempotencyKey))
                .orElse(null);
        if (next == null) return;
        next.attemptStartCursor = lastCursor(repository.findEvents(state.context.sessionId()));
        next.dispatched = true;
        try {
            runtime.send(new ConversationRuntimePort.Message(state.context.sessionId(), next.idempotencyKey,
                    next.content));
        } catch (RuntimeException error) {
            next.dispatched = false;
            repository.updateMessageStatus(state.context.sessionId(), next.idempotencyKey,
                    ConversationRepository.MessageStatus.RECOVERY_REQUIRED);
            next.status = ConversationRepository.MessageStatus.RECOVERY_REQUIRED;
            throw error;
        }
    }

    private void dispatchTask(SessionState state, SendRecord record, io.agentteams.manager.CreateTaskIntent intent,
            String idempotencyKey) {
        try {
            TaskCreationResult task = taskBridge.createAndQueue(state.context, state.owner, intent, idempotencyKey);
            Instant now = Instant.now();
            String data = "{\"taskId\":\"" + task.taskId() + "\",\"phase\":\"" + task.phase()
                    + "\",\"version\":" + task.version() + "}";
            long cursor = lastCursor(repository.findEvents(state.context.sessionId())) + 1;
            ConversationEvent created = repository.appendEvent(state.context.sessionId(),
                    ConversationEvent.of(state.context.sessionId(), cursor, "task.created", data, now));
            repository.appendEvent(state.context.sessionId(),
                    ConversationEvent.of(state.context.sessionId(), created.cursor() + 1, "task.updated", data, now));
            ConversationEvent completed = repository.appendEvent(state.context.sessionId(),
                    ConversationEvent.of(state.context.sessionId(), created.cursor() + 2, "message.completed",
                            "{\"taskId\":\"" + task.taskId() + "\",\"phase\":\"" + task.phase()
                                    + "\",\"text\":\"task queued\"}", now));
            record.dispatched = true;
            record.freezeAt(completed.cursor());
            record.status = ConversationRepository.MessageStatus.COMPLETED;
            repository.updateMessageEnd(state.context.sessionId(), idempotencyKey, completed.cursor());
        } catch (RuntimeException error) {
            record.dispatched = false;
            record.status = ConversationRepository.MessageStatus.RECOVERY_REQUIRED;
            repository.updateMessageStatus(state.context.sessionId(), idempotencyKey,
                    ConversationRepository.MessageStatus.RECOVERY_REQUIRED);
            throw error;
        }
    }

    private void dispatchRoutedMessage(SessionState state, SendRecord record, String content, String idempotencyKey) {
        CreateTaskIntent pendingTask = pendingTaskProposal(state.context.sessionId());
        if (pendingTask != null) {
            if (isTaskConfirmation(content)) {
                dispatchTask(state, record, pendingTask, idempotencyKey);
                return;
            }
            if (isTaskCancellation(content)) {
                completeRoutedMessage(state, record, idempotencyKey,
                        "已取消这项任务预览，未创建或排队任何任务。", "TASK_PREVIEW_CANCELLED");
                return;
            }
            completeRoutedMessage(state, record, idempotencyKey,
                    "有一项后台任务等待确认。请回复“确认执行”创建并排队，或回复“取消任务”；我不会在确认前执行。",
                    "TASK_PREVIEW_WAITING");
            return;
        }
        ConversationIntent intent;
        try {
            intent = intentRouter.route(state.context, state.owner, content);
            if (intent == null) throw new IllegalStateException("conversation intent router returned null");
        } catch (RuntimeException error) {
            record.dispatched = false;
            record.status = ConversationRepository.MessageStatus.FAILED;
            repository.updateMessageStatus(state.context.sessionId(), idempotencyKey,
                    ConversationRepository.MessageStatus.FAILED);
            throw error;
        }
        if (intent.type() == ConversationIntent.Type.CREATE_TASK) {
            dispatchTaskProposal(state, record, intent.task(), idempotencyKey);
            return;
        }
        completeRoutedMessage(state, record, idempotencyKey, intent.response(), intent.type().name());
    }

    private void dispatchTaskProposal(SessionState state, SendRecord record, CreateTaskIntent intent,
            String idempotencyKey) {
        try {
            Instant now = Instant.now();
            long cursor = lastCursor(repository.findEvents(state.context.sessionId())) + 1;
            String capabilitySummary = intent.requiredCapabilities().isEmpty()
                    ? "未指定" : String.join("、", intent.requiredCapabilities());
            String preview = "任务预览\n标题：" + intent.title() + "\n说明：" + intent.description()
                    + "\n所需能力：" + capabilitySummary
                    + "\n确认后将创建并排队。回复“确认执行”继续，或回复“取消任务”。";
            var data = JSON.createObjectNode().put("text", preview).put("intent", "TASK_PREVIEW");
            data.set("task", JSON.valueToTree(intent));
            ConversationEvent completed = repository.appendEvent(state.context.sessionId(),
                    ConversationEvent.of(state.context.sessionId(), cursor, "message.completed", data.toString(), now));
            record.dispatched = true;
            record.freezeAt(completed.cursor());
            record.status = ConversationRepository.MessageStatus.COMPLETED;
            repository.updateMessageEnd(state.context.sessionId(), idempotencyKey, completed.cursor());
        } catch (RuntimeException error) {
            record.dispatched = false;
            record.status = ConversationRepository.MessageStatus.RECOVERY_REQUIRED;
            repository.updateMessageStatus(state.context.sessionId(), idempotencyKey,
                    ConversationRepository.MessageStatus.RECOVERY_REQUIRED);
            throw error;
        }
    }

    private void completeRoutedMessage(SessionState state, SendRecord record, String idempotencyKey,
            String response, String intent) {
        try {
            Instant now = Instant.now();
            long cursor = lastCursor(repository.findEvents(state.context.sessionId())) + 1;
            String data = "{\"text\":" + jsonString(response)
                    + ",\"intent\":" + jsonString(intent) + "}";
            ConversationEvent completed = repository.appendEvent(state.context.sessionId(),
                    ConversationEvent.of(state.context.sessionId(), cursor, "message.completed", data, now));
            record.dispatched = true;
            record.freezeAt(completed.cursor());
            record.status = ConversationRepository.MessageStatus.COMPLETED;
            repository.updateMessageEnd(state.context.sessionId(), idempotencyKey, completed.cursor());
        } catch (RuntimeException error) {
            record.dispatched = false;
            record.status = ConversationRepository.MessageStatus.RECOVERY_REQUIRED;
            repository.updateMessageStatus(state.context.sessionId(), idempotencyKey,
                    ConversationRepository.MessageStatus.RECOVERY_REQUIRED);
            throw error;
        }
    }

    private CreateTaskIntent pendingTaskProposal(UUID sessionId) {
        List<ConversationEvent> events = repository.findEvents(sessionId);
        for (int index = events.size() - 1; index >= 0; index--) {
            ConversationEvent event = events.get(index);
            if ("task.created".equals(event.type())) return null;
            if (!"message.completed".equals(event.type())) continue;
            try {
                JsonNode data = JSON.readTree(event.data());
                String intent = data.path("intent").asText();
                if ("TASK_PREVIEW".equals(intent)) {
                    JsonNode task = data.path("task");
                    if (task.isMissingNode() || task.isNull()) {
                        throw new IllegalStateException("task preview is missing its task payload");
                    }
                    return JSON.treeToValue(task, CreateTaskIntent.class);
                }
                if ("TASK_PREVIEW_CANCELLED".equals(intent)) return null;
            } catch (java.io.IOException error) {
                throw new IllegalStateException("pending task preview could not be read", error);
            }
        }
        return null;
    }

    private static boolean isTaskConfirmation(String content) {
        return content != null && content.trim().matches("(?i)(确认|确认执行|确认创建|开始执行|执行|yes|confirm)[。！.!！]?");
    }

    private static boolean isTaskCancellation(String content) {
        return content != null && content.trim().matches("(?i)(取消|取消任务|暂不创建|不执行|no|cancel)[。！.!！]?");
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static Conversation snapshot(SessionState state) {
        return new Conversation(state.context.sessionId(), state.context, state.status, state.owner, state.version);
    }

    private String lastMessage(UUID sessionId) {
        return repository.findMessages(sessionId).stream()
                .max(java.util.Comparator.comparing(ConversationRepository.MessageRecord::createdAt)
                        .thenComparing(ConversationRepository.MessageRecord::idempotencyKey))
                .map(ConversationRepository.MessageRecord::content)
                .orElse(null);
    }

    private static String encodeCursor(ConversationRepository.ConversationRecord record) {
        String value = record.updatedAt() + "|" + record.context().sessionId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static CursorPosition decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return new CursorPosition(null, null);
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            return new CursorPosition(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("cursor is invalid", error);
        }
    }

    private record CursorPosition(Instant updatedAt, UUID id) {
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

    private void bridgeMessage(SessionState state, String content, String idempotencyKey, Instant occurredAt,
            long managerVersion) {
        if (state.owner == null || state.controlPlaneMessageKeys.contains(idempotencyKey)) return;
        ConversationRuntimePort.Context context = state.context;
        controlPlane.appendMessage(new ControlPlaneConversationPort.MessageRequest(
                context.sessionId(), state.owner.tenantId(), context.project(), context.team(),
                state.owner.subject(), "INTERNAL", "TEXT", content, "PUBLIC", idempotencyKey,
                managerVersion, occurredAt));
        state.controlPlaneMessageKeys.add(idempotencyKey);
    }

    private static final class SessionState {
        private final ConversationRuntimePort.Context context;
        private final ConversationOwner owner;
        private final Map<String, SendRecord> messages = new ConcurrentHashMap<>();
        private final Map<String, Boolean> cancelKeys = new ConcurrentHashMap<>();
        private final java.util.Set<String> controlPlaneMessageKeys = ConcurrentHashMap.newKeySet();
        private Status status;
        private long version;
        private boolean runtimeStarted;
        private boolean controlPlaneCreated;
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
        private final Instant createdAt;
        private long attemptStartCursor;
        private List<ConversationEvent> responseEvents = List.of();
        private boolean responseCaptured;
        private Long endCursor;
        private ConversationRepository.MessageStatus status;
        private boolean dispatched;
        private int retryCount;

        private SendRecord(String idempotencyKey, String content, long startCursor, Instant createdAt, Long endCursor,
                ConversationRepository.MessageStatus status) {
            this.idempotencyKey = idempotencyKey;
            this.content = content;
            this.startCursor = startCursor;
            this.createdAt = createdAt;
            this.attemptStartCursor = startCursor;
            this.endCursor = endCursor;
            this.status = status;
        }

        private static SendRecord from(ConversationRepository.MessageRecord record) {
            return new SendRecord(record.idempotencyKey(), record.content(), record.startCursor(), record.createdAt(), record.endCursor(),
                    record.status());
        }

        private void freezeAt(long cursor) {
            endCursor = cursor;
        }
    }
}
