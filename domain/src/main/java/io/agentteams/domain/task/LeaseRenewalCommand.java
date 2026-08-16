package io.agentteams.domain.task;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record LeaseRenewalCommand(
        UUID eventId,
        long expectedVersion,
        UUID attemptId,
        UUID leaseId,
        Instant occurredAt,
        Instant requestedExpiry,
        String actor,
        String source) {

    public LeaseRenewalCommand {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(leaseId, "leaseId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(requestedExpiry, "requestedExpiry");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(source, "source");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }
}
