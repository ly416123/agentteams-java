package io.agentteams.domain.task;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TaskTransitionCommand(
        UUID eventId,
        long expectedVersion,
        TaskPhase targetPhase,
        UUID attemptId,
        UUID leaseId,
        Instant occurredAt,
        Instant leaseExpiresAt,
        String actor,
        String source,
        FailureInfo failure) {

    public TaskTransitionCommand {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(targetPhase, "targetPhase");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(source, "source");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }

    public static TaskTransitionCommand simple(
            UUID eventId,
            long expectedVersion,
            TaskPhase targetPhase,
            Instant occurredAt,
            String actor,
            String source) {
        return new TaskTransitionCommand(eventId, expectedVersion, targetPhase, null, null,
                occurredAt, null, actor, source, null);
    }

    public static TaskTransitionCommand assign(
            UUID eventId,
            long expectedVersion,
            UUID attemptId,
            UUID leaseId,
            Instant occurredAt,
            Instant leaseExpiresAt,
            String actor,
            String source) {
        return new TaskTransitionCommand(eventId, expectedVersion, TaskPhase.ASSIGNED, attemptId, leaseId,
                occurredAt, leaseExpiresAt, actor, source, null);
    }

    public static TaskTransitionCommand failed(
            UUID eventId,
            long expectedVersion,
            UUID attemptId,
            UUID leaseId,
            Instant occurredAt,
            String actor,
            String source,
            FailureInfo failure) {
        return new TaskTransitionCommand(eventId, expectedVersion, TaskPhase.FAILED, attemptId, leaseId,
                occurredAt, null, actor, source, Objects.requireNonNull(failure, "failure"));
    }

    public static TaskTransitionCommand forAttempt(
            UUID eventId,
            long expectedVersion,
            TaskPhase targetPhase,
            UUID attemptId,
            UUID leaseId,
            Instant occurredAt,
            String actor,
            String source) {
        return new TaskTransitionCommand(eventId, expectedVersion, targetPhase, attemptId, leaseId,
                occurredAt, null, actor, source, null);
    }
}
