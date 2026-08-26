package io.agentteams.domain.task;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A runtime rejected a delivered assignment; the assigned attempt must be reclaimed. */
public record RejectionCommand(
        UUID eventId,
        long expectedVersion,
        UUID attemptId,
        UUID leaseId,
        Instant occurredAt,
        String actor,
        String source,
        String rejectionReason) {

    public RejectionCommand {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(leaseId, "leaseId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(rejectionReason, "rejectionReason");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }
}
