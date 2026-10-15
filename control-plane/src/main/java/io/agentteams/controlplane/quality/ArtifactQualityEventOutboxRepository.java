package io.agentteams.controlplane.quality;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Durable retry queue for quality events whose primary projection is temporarily unavailable. */
public interface ArtifactQualityEventOutboxRepository {
    boolean enqueue(ArtifactQualityEvent event);

    List<PendingEvent> claimDue(Instant now, Duration lease, int limit);

    void markDelivered(UUID eventId);

    void retry(UUID eventId, Instant nextAttemptAt, String failureType);

    int pendingCount();

    record PendingEvent(ArtifactQualityEvent event, int attempts) { }
}
