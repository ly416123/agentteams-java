package io.agentteams.controlplane.outbox;

import io.agentteams.controlplane.persistence.OutboxEventRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxStore {

    /** Returns non-terminal rows currently retained by the relay. */
    default long pendingCount() {
        return -1;
    }

    /** Returns the creation time of the oldest non-terminal row, when one exists. */
    default Optional<Instant> oldestPendingAt() {
        return Optional.empty();
    }

    List<OutboxEventRecord> claimDue(Instant now, int limit, Duration lease);

    void markPublished(OutboxEventRecord event, Instant at);

    void markRetry(OutboxEventRecord event, Instant nextAttemptAt, String error, Instant at);

    void markDeadLetter(OutboxEventRecord event, Instant at);
}
