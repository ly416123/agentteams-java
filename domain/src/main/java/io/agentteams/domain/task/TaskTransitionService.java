package io.agentteams.domain.task;

import java.time.Instant;
import java.util.Objects;

/** Applies task commands while enforcing legal phases, versions, idempotency, and leases. */
public final class TaskTransitionService {

    private final CancellationPolicy cancellationPolicy;

    public TaskTransitionService() {
        this(CancellationPolicy.QUEUED_AND_ASSIGNED);
    }

    public TaskTransitionService(CancellationPolicy cancellationPolicy) {
        this.cancellationPolicy = Objects.requireNonNull(cancellationPolicy, "cancellationPolicy");
    }

    public TaskTransitionResult transition(Task task, TaskTransitionCommand command) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(command, "command");

        if (task.hasProcessedEvent(command.eventId())) {
            return new DuplicateTransition(command.eventId(), task);
        }
        checkVersion(task, command.expectedVersion());
        TaskPhase from = task.phase();
        TaskPhase to = command.targetPhase();
        if (!legal(from, to)) {
            throw new IllegalTaskTransitionException(from, to);
        }

        TaskAttempt nextAttempt = task.attempt();
        if (to == TaskPhase.ASSIGNED) {
            nextAttempt = createAttempt(task, command);
        } else if (requiresExecutionAttempt(to)) {
            nextAttempt = requireActiveExecutionAttempt(task, command);
        }

        if (to == TaskPhase.FAILED && command.failure() == null) {
            throw new IllegalArgumentException("failure details are required for FAILED");
        }
        if (to != TaskPhase.FAILED && command.failure() != null) {
            throw new IllegalArgumentException("failure details are only valid for FAILED");
        }

        if (nextAttempt != null && to != TaskPhase.ASSIGNED) {
            nextAttempt = nextAttempt.transitionTo(to, command.occurredAt(), command.actor(), command.source(),
                    command.failure());
        }
        Task next = task.next(to, nextAttempt, command.occurredAt(), command.actor(), command.source(),
                command.failure(), command.eventId());
        return new AppliedTransition(command.eventId(), next, from, to);
    }

    public TaskTransitionResult renewLease(Task task, LeaseRenewalCommand command) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(command, "command");

        if (task.hasProcessedEvent(command.eventId())) {
            return new DuplicateTransition(command.eventId(), task);
        }
        checkVersion(task, command.expectedVersion());
        TaskAttempt attempt = task.attempt();
        if (attempt == null) {
            throw new LeaseNotActiveException(LeaseNotActiveReason.MISSING,
                    "Heartbeat requires an active attempt and lease");
        }
        if (!attempt.id().equals(command.attemptId()) || !attempt.leaseId().equals(command.leaseId())) {
            throw new LeaseNotActiveException(LeaseNotActiveReason.MISMATCH,
                    "Heartbeat does not match the active attempt and lease");
        }
        if (attempt.completedAt() != null) {
            throw new LeaseNotActiveException(LeaseNotActiveReason.COMPLETED,
                "Lease belongs to a completed attempt");
        }
        if (!attempt.leaseExpiresAt().isAfter(command.occurredAt())) {
            throw new LeaseNotActiveException(LeaseNotActiveReason.EXPIRED,
                    "Lease " + attempt.leaseId() + " is expired");
        }
        if (!command.requestedExpiry().isAfter(attempt.leaseExpiresAt())) {
            throw new LeaseNotActiveException(LeaseNotActiveReason.NOT_EXTENDING,
                    "Lease renewal must extend the current expiry");
        }
        TaskAttempt renewed = attempt.renewLease(command.occurredAt(), command.requestedExpiry(), command.actor(),
                command.source());
        Task next = task.nextLease(renewed, command.occurredAt(), command.actor(), command.source(), command.eventId());
        return new AppliedTransition(command.eventId(), next, task.phase(), task.phase());
    }

    private TaskAttempt createAttempt(Task task, TaskTransitionCommand command) {
        if (command.attemptId() == null || command.leaseId() == null) {
            throw new LeaseNotActiveException(LeaseNotActiveReason.MISSING,
                    "ASSIGNED requires an attempt ID and lease ID");
        }
        Instant expiry = command.leaseExpiresAt();
        if (expiry == null || !expiry.isAfter(command.occurredAt())) {
            throw new LeaseNotActiveException(LeaseNotActiveReason.EXPIRED,
                    "ASSIGNED requires a future lease expiry");
        }
        return new TaskAttempt(command.attemptId(), task.id(), command.leaseId(), TaskPhase.ASSIGNED,
                command.occurredAt(), command.occurredAt(), expiry, null, command.actor(), command.source(),
                null, null, 0);
    }

    private TaskAttempt requireActiveExecutionAttempt(Task task, TaskTransitionCommand command) {
        TaskAttempt attempt = task.attempt();
        if (attempt == null) {
            throw new LeaseNotActiveException(LeaseNotActiveReason.MISSING,
                    "Execution transition requires an attempt and lease");
        }
        if (command.attemptId() == null || command.leaseId() == null) {
            throw new LeaseNotActiveException(LeaseNotActiveReason.MISSING,
                    "Execution transition must identify its attempt and lease");
        }
        if (!attempt.id().equals(command.attemptId()) || !attempt.leaseId().equals(command.leaseId())) {
            throw new LeaseNotActiveException(LeaseNotActiveReason.MISMATCH,
                "Transition does not match the active attempt and lease");
        }
        if (attempt.completedAt() != null) {
            throw new LeaseNotActiveException(LeaseNotActiveReason.COMPLETED,
                    "Transition belongs to a completed attempt");
        }
        if (!attempt.leaseExpiresAt().isAfter(command.occurredAt())) {
            throw new LeaseNotActiveException(LeaseNotActiveReason.EXPIRED,
                    "Lease " + attempt.leaseId() + " is expired");
        }
        return attempt;
    }

    private boolean requiresExecutionAttempt(TaskPhase phase) {
        return phase == TaskPhase.ACCEPTED || phase == TaskPhase.RUNNING
                || phase == TaskPhase.SUCCEEDED || phase == TaskPhase.FAILED;
    }

    private void checkVersion(Task task, long expectedVersion) {
        if (task.version() != expectedVersion) {
            throw new StaleTaskVersionException(expectedVersion, task.version());
        }
    }

    private boolean legal(TaskPhase from, TaskPhase to) {
        return switch (from) {
            case DRAFT -> to == TaskPhase.QUEUED;
            case QUEUED -> to == TaskPhase.ASSIGNED
                    || (to == TaskPhase.CANCELLED && cancellationPolicy.allows(from));
            case ASSIGNED -> to == TaskPhase.ACCEPTED
                    || (to == TaskPhase.CANCELLED && cancellationPolicy.allows(from));
            case ACCEPTED -> to == TaskPhase.RUNNING;
            case RUNNING -> to == TaskPhase.SUCCEEDED || to == TaskPhase.FAILED;
            case SUCCEEDED, FAILED, CANCELLED -> false;
        };
    }

    public enum CancellationPolicy {
        QUEUED_ONLY,
        QUEUED_AND_ASSIGNED;

        boolean allows(TaskPhase phase) {
            return this == QUEUED_AND_ASSIGNED || phase == TaskPhase.QUEUED;
        }
    }
}
