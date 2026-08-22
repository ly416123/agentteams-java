package io.agentteams.controlplane.outbox;

import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.OutboxEventRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class JdbcOutboxStore implements OutboxStore {

    private final FoundationPersistenceService persistence;

    public JdbcOutboxStore(FoundationPersistenceService persistence) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
    }

    @Override
    public long pendingCount() {
        return persistence.inTransaction(tx -> tx.outboxEvents().pendingCount());
    }

    @Override
    public Optional<Instant> oldestPendingAt() {
        return persistence.inTransaction(tx -> tx.outboxEvents().oldestPendingAt());
    }

    @Override
    public List<OutboxEventRecord> claimDue(Instant now, int limit, Duration lease) {
        return persistence.inTransaction(tx -> tx.outboxEvents().claimDue(now, limit, lease));
    }

    @Override
    public void markPublished(OutboxEventRecord event, Instant at) {
        persistence.inTransaction(tx -> {
            tx.outboxEvents().markPublished(event, at);
            return null;
        });
    }

    @Override
    public void markRetry(OutboxEventRecord event, Instant nextAttemptAt, String error, Instant at) {
        persistence.inTransaction(tx -> {
            tx.outboxEvents().markRetry(event, nextAttemptAt, error, at);
            return null;
        });
    }

    @Override
    public void markDeadLetter(OutboxEventRecord event, Instant at) {
        persistence.inTransaction(tx -> {
            tx.outboxEvents().markDeadLetter(event, "dead-lettered after max attempts", at);
            return null;
        });
    }
}
