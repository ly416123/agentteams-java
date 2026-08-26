package io.agentteams.manager.session;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ManagerSessionRepository {
    ManagerSessionRecord insertSession(ManagerSessionRecord session, String idempotencyKey);

    Optional<ManagerSessionRecord> findSession(UUID id);

    Optional<ManagerMessageRecord> findMessage(UUID sessionId, String idempotencyKey);

    void insertMessage(ManagerMessageRecord message);

    Optional<ManagerToolCallRecord> findToolCall(UUID sessionId, String idempotencyKey);

    void insertToolCall(ManagerToolCallRecord toolCall);

    ManagerSessionRecord updateSession(UUID id, long expectedVersion,
            ManagerSessionRecord.Status status, Instant updatedAt);

    default ManagerEventRecord appendEvent(UUID sessionId, String type, String payload, Instant createdAt) {
        return appendEvent(sessionId, type, payload, null, createdAt);
    }

    ManagerEventRecord appendEvent(UUID sessionId, String type, String payload, String idempotencyKey,
            Instant createdAt);

    Optional<ManagerEventRecord> findEvent(UUID sessionId, String idempotencyKey);

    List<ManagerEventRecord> findEventsAfter(UUID sessionId, long cursor);
}
