package io.agentteams.domain.task;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable execution attempt and its current lease metadata. */
public record TaskAttempt(
        UUID id,
        UUID taskId,
        UUID leaseId,
        TaskPhase phase,
        Instant createdAt,
        Instant updatedAt,
        Instant leaseExpiresAt,
        Instant completedAt,
        String actor,
        String source,
        String failureCode,
        String redactedFailureMessage,
        long version) {

    public TaskAttempt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(leaseId, "leaseId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(source, "source");
        if (version < 0) {
            throw new IllegalArgumentException("attempt version must not be negative");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
        if (leaseExpiresAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("leaseExpiresAt must not precede createdAt");
        }
        if (phase == TaskPhase.DRAFT || phase == TaskPhase.QUEUED) {
            throw new IllegalArgumentException("attempt phase must represent execution");
        }
        if (phase.terminal() && completedAt == null) {
            throw new IllegalArgumentException("terminal attempt must have completedAt");
        }
        if (!phase.terminal() && completedAt != null) {
            throw new IllegalArgumentException("nonterminal attempt must not have completedAt");
        }
        if (phase == TaskPhase.FAILED) {
            requireText(failureCode, "failureCode");
            requireText(redactedFailureMessage, "redactedFailureMessage");
        } else if (failureCode != null || redactedFailureMessage != null) {
            throw new IllegalArgumentException("only FAILED attempts may carry failure details");
        }
    }

    public boolean leaseActiveAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return completedAt == null && leaseExpiresAt.isAfter(now);
    }

    public TaskAttempt transitionTo(
            TaskPhase nextPhase,
            Instant at,
            String nextActor,
            String nextSource,
            FailureInfo failure) {
        Instant finishedAt = nextPhase.terminal() ? at : completedAt;
        return new TaskAttempt(
                id,
                taskId,
                leaseId,
                nextPhase,
                createdAt,
                at,
                leaseExpiresAt,
                finishedAt,
                nextActor,
                nextSource,
                failure == null ? null : failure.code(),
                failure == null ? null : failure.redactedMessage(),
                version);
    }

    public TaskAttempt renewLease(Instant at, Instant nextExpiry, String nextActor, String nextSource) {
        return new TaskAttempt(
                id,
                taskId,
                leaseId,
                phase,
                createdAt,
                at,
                nextExpiry,
                completedAt,
                nextActor,
                nextSource,
                failureCode,
                redactedFailureMessage,
                version);
    }

    TaskAttempt withVersion(long nextVersion) {
        return new TaskAttempt(id, taskId, leaseId, phase, createdAt, updatedAt, leaseExpiresAt, completedAt,
                actor, source, failureCode, redactedFailureMessage, nextVersion);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
