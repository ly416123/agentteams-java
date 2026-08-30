package io.agentteams.manager.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable boundary for conversation state, messages and replayable events. */
public interface ConversationRepository {
    ConversationRecord saveSession(ConversationRuntimePort.Context context, ConversationService.Status status,
            Instant createdAt, String idempotencyKey);

    default ConversationRecord saveSession(ConversationRuntimePort.Context context, ConversationService.Status status,
            Instant createdAt) {
        return saveSession(context, status, createdAt, null);
    }

    ConversationRecord saveSession(ConversationRuntimePort.Context context, ConversationService.Status status,
            Instant createdAt, String idempotencyKey, ConversationOwner owner);

    Optional<ConversationRecord> findSession(UUID sessionId);

    Optional<ConversationRecord> findSessionByIdempotencyKey(String idempotencyKey);

    default ConversationRecord updateStatus(UUID sessionId, ConversationService.Status status, Instant updatedAt) {
        ConversationRecord current = findSession(sessionId).orElseThrow(ConversationRuntimeException::sessionNotFound);
        return updateStatus(sessionId, status, updatedAt, current.version());
    }

    ConversationRecord updateStatus(UUID sessionId, ConversationService.Status status, Instant updatedAt,
            long expectedVersion);

    Optional<MessageRecord> findMessage(UUID sessionId, String idempotencyKey);

    List<MessageRecord> findMessages(UUID sessionId);

    /** Atomically claims an idempotency key and consumes the expected session version. */
    MessageReservation reserveMessage(UUID sessionId, long expectedVersion, MessageRecord message);

    MessageRecord saveMessage(UUID sessionId, String idempotencyKey, String content, long startCursor,
            Instant createdAt);

    MessageRecord updateMessageEnd(UUID sessionId, String idempotencyKey, long endCursor);

    MessageRecord updateMessageStatus(UUID sessionId, String idempotencyKey, MessageStatus status);

    List<ConversationEvent> findEvents(UUID sessionId, long afterCursor);

    List<ConversationEvent> findEvents(UUID sessionId);

    ConversationEvent appendEvent(UUID sessionId, ConversationEvent event);

    record ConversationRecord(ConversationRuntimePort.Context context, ConversationService.Status status,
            Instant createdAt, Instant updatedAt, ConversationOwner owner, long version) {
        public ConversationRecord(ConversationRuntimePort.Context context, ConversationService.Status status,
                Instant createdAt, Instant updatedAt) {
            this(context, status, createdAt, updatedAt, null, 0);
        }
    }

    enum MessageStatus { RESERVED, COMPLETED, FAILED, RECOVERY_REQUIRED }

    record MessageRecord(UUID sessionId, String idempotencyKey, String content, long startCursor, Long endCursor,
            Instant createdAt, MessageStatus status) {
        public MessageRecord(UUID sessionId, String idempotencyKey, String content, long startCursor, Long endCursor,
                Instant createdAt) {
            this(sessionId, idempotencyKey, content, startCursor, endCursor, createdAt, MessageStatus.COMPLETED);
        }
    }

    record MessageReservation(ConversationRecord session, MessageRecord message, boolean acquired) {
    }
}
