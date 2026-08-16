package io.agentteams.domain.task;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable task aggregate used by the domain transition service. */
public record Task(
        UUID id,
        TaskPhase phase,
        long version,
        TaskAttempt attempt,
        Instant createdAt,
        Instant updatedAt,
        String actor,
        String source,
        String failureCode,
        String redactedFailureMessage,
        Set<UUID> processedEventIds) {

    public Task {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(source, "source");
        if (version < 0) {
            throw new IllegalArgumentException("task version must not be negative");
        }
        if (attempt != null && !id.equals(attempt.taskId())) {
            throw new IllegalArgumentException("attempt belongs to another task");
        }
        if (attempt != null && attempt.version() != version) {
            throw new IllegalArgumentException("task and attempt versions must be aligned");
        }
        if ((phase == TaskPhase.DRAFT || phase == TaskPhase.QUEUED) && attempt != null) {
            throw new IllegalArgumentException(phase + " tasks must not carry an attempt");
        }
        if (attempt != null && attempt.phase() != phase) {
            throw new IllegalArgumentException("task and attempt phases must be aligned");
        }
        if (phase == TaskPhase.FAILED) {
            requireText(failureCode, "failureCode");
            requireText(redactedFailureMessage, "redactedFailureMessage");
        } else if (failureCode != null || redactedFailureMessage != null) {
            throw new IllegalArgumentException("only FAILED tasks may carry failure details");
        }
        processedEventIds = Set.copyOf(new LinkedHashSet<>(Objects.requireNonNull(processedEventIds,
                "processedEventIds")));
    }

    public static Task draft(UUID id, Instant createdAt) {
        return draft(id, createdAt, "system", "domain");
    }

    public static Task draft(UUID id, Instant createdAt, String actor, String source) {
        return new Task(id, TaskPhase.DRAFT, 0, null, createdAt, createdAt,
                actor, source, null, null, Set.of());
    }

    public boolean hasProcessedEvent(UUID eventId) {
        return processedEventIds.contains(eventId);
    }

    Task next(
            TaskPhase nextPhase,
            TaskAttempt nextAttempt,
            Instant at,
            String nextActor,
            String nextSource,
            FailureInfo failure,
            UUID eventId) {
        long nextVersion = nextVersion();
        TaskAttempt versionedAttempt = nextAttempt == null ? null : nextAttempt.withVersion(nextVersion);
        LinkedHashSet<UUID> nextEvents = new LinkedHashSet<>(processedEventIds);
        nextEvents.add(eventId);
        return new Task(
                id,
                nextPhase,
                nextVersion,
                versionedAttempt,
                createdAt,
                at,
                nextActor,
                nextSource,
                failure == null ? null : failure.code(),
                failure == null ? null : failure.redactedMessage(),
                nextEvents);
    }

    Task nextLease(
            TaskAttempt nextAttempt,
            Instant at,
            String nextActor,
            String nextSource,
            UUID eventId) {
        return next(phase, nextAttempt, at, nextActor, nextSource,
                failureCode == null ? null : FailureInfo.redacted(failureCode, redactedFailureMessage), eventId);
    }

    private long nextVersion() {
        return Math.addExact(version, 1);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
