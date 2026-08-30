package io.agentteams.manager.conversation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Small durable-boundary substitute used by unit tests and the local fake runtime. */
public final class InMemoryConversationRepository implements ConversationRepository {
    private final Map<UUID, ConversationRecord> sessions = new ConcurrentHashMap<>();
    private final Map<String, UUID> createKeys = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, MessageRecord>> messages = new ConcurrentHashMap<>();
    private final Map<UUID, List<ConversationEvent>> events = new ConcurrentHashMap<>();

    @Override
    public ConversationRecord saveSession(ConversationRuntimePort.Context context, ConversationService.Status status,
            Instant createdAt, String idempotencyKey) {
        return saveSession(context, status, createdAt, idempotencyKey, null);
    }

    @Override
    public ConversationRecord saveSession(ConversationRuntimePort.Context context, ConversationService.Status status,
            Instant createdAt, String idempotencyKey, ConversationOwner owner) {
        if (idempotencyKey != null) {
            UUID existingId = createKeys.get(idempotencyKey);
            if (existingId != null) return sessions.get(existingId);
        }
        ConversationRecord candidate = new ConversationRecord(context, status, createdAt, createdAt, owner, 0);
        ConversationRecord result = sessions.compute(context.sessionId(), (ignored, existing) -> {
            if (existing == null) return candidate;
            if (!existing.context().equals(context)) {
                throw new ConversationRuntimeException(ConversationRuntimeException.Code.IDEMPOTENCY_CONFLICT,
                        "conversation session already exists with different context");
            }
            if (owner != null && existing.owner() != null && !existing.owner().equals(owner)) {
                throw new ConversationRuntimeException(ConversationRuntimeException.Code.IDEMPOTENCY_CONFLICT,
                        "conversation session already exists with different owner");
            }
            return existing;
        });
        if (idempotencyKey != null) createKeys.putIfAbsent(idempotencyKey, context.sessionId());
        return result;
    }

    @Override
    public Optional<ConversationRecord> findSession(UUID sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public Optional<ConversationRecord> findSessionByIdempotencyKey(String idempotencyKey) {
        UUID sessionId = createKeys.get(idempotencyKey);
        return sessionId == null ? Optional.empty() : findSession(sessionId);
    }

    @Override
    public ConversationRecord updateStatus(UUID sessionId, ConversationService.Status status, Instant updatedAt) {
        ConversationRecord current = sessions.get(sessionId);
        if (current == null) throw ConversationRuntimeException.sessionNotFound();
        return updateStatus(sessionId, status, updatedAt, current.version());
    }

    @Override
    public ConversationRecord updateStatus(UUID sessionId, ConversationService.Status status, Instant updatedAt,
            long expectedVersion) {
        ConversationRecord[] result = new ConversationRecord[1];
        sessions.compute(sessionId, (ignored, existing) -> {
            if (existing == null) throw ConversationRuntimeException.sessionNotFound();
            if (existing.version() != expectedVersion) {
                throw new ConversationVersionConflictException(sessionId, expectedVersion, existing.version());
            }
            result[0] = new ConversationRecord(existing.context(), status, existing.createdAt(), updatedAt,
                    existing.owner(), existing.version() + 1);
            return result[0];
        });
        return result[0];
    }

    @Override
    public Optional<MessageRecord> findMessage(UUID sessionId, String idempotencyKey) {
        return Optional.ofNullable(messages.getOrDefault(sessionId, Map.of()).get(idempotencyKey));
    }

    @Override
    public List<MessageRecord> findMessages(UUID sessionId) {
        return List.copyOf(messages.getOrDefault(sessionId, Map.of()).values());
    }

    @Override
    public MessageReservation reserveMessage(UUID sessionId, long expectedVersion, MessageRecord message) {
        synchronized (sessions) {
            ConversationRecord current = sessions.get(sessionId);
            if (current == null) throw ConversationRuntimeException.sessionNotFound();
            MessageRecord existing = findMessage(sessionId, message.idempotencyKey()).orElse(null);
            if (existing != null) return new MessageReservation(current, existing, false);
            if (current.status() == ConversationService.Status.CANCELLED) {
                throw new ConversationRuntimeException(ConversationRuntimeException.Code.CANCELLED,
                        "conversation has been cancelled");
            }
            if (current.version() != expectedVersion) {
                throw new ConversationVersionConflictException(sessionId, expectedVersion, current.version());
            }
            long startCursor = events.getOrDefault(sessionId, List.of()).stream()
                    .mapToLong(ConversationEvent::cursor).max().orElse(0);
            MessageRecord reserved = new MessageRecord(sessionId, message.idempotencyKey(), message.content(),
                    startCursor, null, message.createdAt(), MessageStatus.RESERVED);
            messages.computeIfAbsent(sessionId, ignored -> new ConcurrentHashMap<>())
                    .put(message.idempotencyKey(), reserved);
            ConversationRecord updated = new ConversationRecord(current.context(), ConversationService.Status.ACTIVE,
                    current.createdAt(), message.createdAt(), current.owner(), current.version() + 1);
            sessions.put(sessionId, updated);
            return new MessageReservation(updated, reserved, true);
        }
    }

    @Override
    public MessageRecord saveMessage(UUID sessionId, String idempotencyKey, String content, long startCursor,
            Instant createdAt) {
        MessageRecord candidate = new MessageRecord(sessionId, idempotencyKey, content, startCursor, null, createdAt);
        MessageRecord result = messages.computeIfAbsent(sessionId, ignored -> new ConcurrentHashMap<>())
                .putIfAbsent(idempotencyKey, candidate);
        return result == null ? candidate : result;
    }

    @Override
    public MessageRecord updateMessageEnd(UUID sessionId, String idempotencyKey, long endCursor) {
        Map<String, MessageRecord> sessionMessages = messages.get(sessionId);
        MessageRecord existing = sessionMessages == null ? null : sessionMessages.get(idempotencyKey);
        if (existing == null) throw ConversationRuntimeException.sessionNotFound();
        MessageRecord updated = new MessageRecord(existing.sessionId(), existing.idempotencyKey(), existing.content(),
                existing.startCursor(), endCursor, existing.createdAt(),
                MessageStatus.COMPLETED);
        sessionMessages.put(idempotencyKey, updated);
        return updated;
    }

    @Override
    public MessageRecord updateMessageStatus(UUID sessionId, String idempotencyKey, MessageStatus status) {
        Map<String, MessageRecord> sessionMessages = messages.get(sessionId);
        MessageRecord existing = sessionMessages == null ? null : sessionMessages.get(idempotencyKey);
        if (existing == null) throw ConversationRuntimeException.sessionNotFound();
        MessageRecord updated = new MessageRecord(existing.sessionId(), existing.idempotencyKey(), existing.content(),
                existing.startCursor(), existing.endCursor(), existing.createdAt(), status);
        sessionMessages.put(idempotencyKey, updated);
        return updated;
    }

    @Override
    public List<ConversationEvent> findEvents(UUID sessionId, long afterCursor) {
        return events.getOrDefault(sessionId, List.of()).stream()
                .filter(event -> event.cursor() > afterCursor).toList();
    }

    @Override
    public List<ConversationEvent> findEvents(UUID sessionId) {
        return List.copyOf(events.getOrDefault(sessionId, List.of()));
    }

    @Override
    public ConversationEvent appendEvent(UUID sessionId, ConversationEvent event) {
        List<ConversationEvent> sessionEvents = events.computeIfAbsent(sessionId,
                ignored -> new ArrayList<>());
        synchronized (sessionEvents) {
            if (event.sourceEventId() != null) {
                ConversationEvent existing = sessionEvents.stream()
                        .filter(candidate -> event.sourceEventId().equals(candidate.sourceEventId()))
                        .findFirst().orElse(null);
                if (existing != null) {
                    if (!existing.type().equals(event.type()) || !existing.data().equals(event.data())) {
                        throw new ConversationRuntimeException(ConversationRuntimeException.Code.PROTOCOL_ERROR,
                                "source event id was reused with different payload");
                    }
                    return existing;
                }
            }
            long cursor = sessionEvents.isEmpty() ? 1 : sessionEvents.get(sessionEvents.size() - 1).cursor() + 1;
            ConversationEvent stored = ConversationEvent.of(sessionId, cursor, event.type(), event.data(),
                    event.occurredAt(), event.sourceEventId());
            sessionEvents.add(stored);
            return stored;
        }
    }
}
