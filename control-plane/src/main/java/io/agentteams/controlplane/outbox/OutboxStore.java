package io.agentteams.controlplane.outbox;

import io.agentteams.controlplane.persistence.OutboxEventRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface OutboxStore {

    List<OutboxEventRecord> claimDue(Instant now, int limit, Duration lease);

    void markPublished(OutboxEventRecord event, Instant at);

    void markRetry(OutboxEventRecord event, Instant nextAttemptAt, String error, Instant at);

    void markDeadLetter(OutboxEventRecord event, Instant at);
}
