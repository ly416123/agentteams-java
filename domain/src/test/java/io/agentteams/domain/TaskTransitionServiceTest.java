package io.agentteams.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentteams.domain.task.DuplicateTransition;
import io.agentteams.domain.task.FailureInfo;
import io.agentteams.domain.task.IllegalTaskTransitionException;
import io.agentteams.domain.task.LeaseNotActiveException;
import io.agentteams.domain.task.LeaseNotActiveReason;
import io.agentteams.domain.task.LeaseRenewalCommand;
import io.agentteams.domain.task.StaleTaskVersionException;
import io.agentteams.domain.task.Task;
import io.agentteams.domain.task.TaskAttempt;
import io.agentteams.domain.task.TaskPhase;
import io.agentteams.domain.task.TaskTransitionCommand;
import io.agentteams.domain.task.TaskTransitionResult;
import io.agentteams.domain.task.TaskTransitionService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskTransitionServiceTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private final TaskTransitionService service = new TaskTransitionService();

    @Test
    void appliesEveryLegalTaskTransitionAndAdvancesTheVersion() {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID leaseId = UUID.randomUUID();
        Task task = Task.draft(taskId, START);

        task = apply(task, TaskTransitionCommand.simple(UUID.randomUUID(), task.version(),
                TaskPhase.QUEUED, at(1), "user-1", "api"));
        assertEquals(TaskPhase.QUEUED, task.phase());
        assertVersionAligned(task);

        task = apply(task, TaskTransitionCommand.assign(UUID.randomUUID(), task.version(),
                attemptId, leaseId, at(2), at(62), "scheduler", "scheduler"));
        assertEquals(TaskPhase.ASSIGNED, task.phase());
        assertEquals(attemptId, task.attempt().id());
        assertEquals(leaseId, task.attempt().leaseId());
        assertVersionAligned(task);

        task = apply(task, execution(task, TaskPhase.ACCEPTED, at(3)));
        assertVersionAligned(task);
        task = apply(task, execution(task, TaskPhase.RUNNING, at(4)));
        assertVersionAligned(task);
        task = apply(task, execution(task, TaskPhase.SUCCEEDED, at(5)));

        assertEquals(TaskPhase.SUCCEEDED, task.phase());
        assertEquals(5, task.version());
        assertEquals("agent-1", task.actor());
        assertEquals("agent", task.source());
        assertEquals(at(5), task.updatedAt());
        assertEquals(at(5), task.attempt().completedAt());
        assertVersionAligned(task);
    }

    @Test
    void appliesRunningToFailedAndPreservesRedactedFailureDetails() {
        Task task = runningTask();
        FailureInfo failure = FailureInfo.fromRaw("RUNTIME_ERROR",
                "token=top-secret; process exited with code 7");

        task = apply(task, TaskTransitionCommand.failed(UUID.randomUUID(), task.version(),
                task.attempt().id(), task.attempt().leaseId(), at(8), "agent-1", "agent", failure));

        assertEquals(TaskPhase.FAILED, task.phase());
        assertEquals("RUNTIME_ERROR", task.failureCode());
        assertTrue(task.redactedFailureMessage().contains("[REDACTED]"));
        assertTrue(!task.redactedFailureMessage().contains("top-secret"));
    }

    @Test
    void allowsQueuedAndAssignedCancellationButNotAcceptedCancellation() {
        Task queued = Task.draft(UUID.randomUUID(), START);
        queued = apply(queued, TaskTransitionCommand.simple(UUID.randomUUID(), queued.version(),
                TaskPhase.QUEUED, at(1), "user-1", "api"));
        queued = apply(queued, TaskTransitionCommand.simple(UUID.randomUUID(), queued.version(),
                TaskPhase.CANCELLED, at(2), "user-1", "api"));
        assertEquals(TaskPhase.CANCELLED, queued.phase());

        Task assigned = assignedTask();
        assigned = apply(assigned, TaskTransitionCommand.simple(UUID.randomUUID(), assigned.version(),
                TaskPhase.CANCELLED, at(3), "user-1", "api"));
        assertEquals(TaskPhase.CANCELLED, assigned.phase());
        assertEquals(at(3), assigned.attempt().completedAt());
        assertVersionAligned(assigned);

        Task accepted = acceptedTask();
        assertThrows(IllegalTaskTransitionException.class, () ->
                service.transition(accepted, TaskTransitionCommand.simple(UUID.randomUUID(), accepted.version(),
                TaskPhase.CANCELLED, at(4), "user-1", "api")));
    }

    @Test
    void supportsPauseResumeRetryAndRejectionBeforeExecution() {
        Task paused = apply(Task.draft(UUID.randomUUID(), START), TaskTransitionCommand.simple(
                UUID.randomUUID(), 0, TaskPhase.PAUSED, at(1), "user-1", "matrix"));
        assertEquals(TaskPhase.PAUSED, paused.phase());

        Task resumed = apply(paused, TaskTransitionCommand.simple(UUID.randomUUID(), paused.version(),
                TaskPhase.QUEUED, at(2), "user-1", "matrix"));
        assertEquals(TaskPhase.QUEUED, resumed.phase());

        Task failed = failedTask();
        Task retried = apply(failed, TaskTransitionCommand.simple(UUID.randomUUID(), failed.version(),
                TaskPhase.QUEUED, at(9), "user-1", "matrix"));
        assertEquals(TaskPhase.QUEUED, retried.phase());
        assertEquals(6, retried.version());

        Task rejected = apply(Task.draft(UUID.randomUUID(), START), TaskTransitionCommand.simple(
                UUID.randomUUID(), 0, TaskPhase.REJECTED, at(3), "reviewer", "matrix"));
        assertEquals(TaskPhase.REJECTED, rejected.phase());
        assertTrue(rejected.phase().terminal());
    }

    @Test
    void allowsCancellingDraftTaskBeforeQueueing() {
        Task cancelled = apply(Task.draft(UUID.randomUUID(), START), TaskTransitionCommand.simple(
                UUID.randomUUID(), 0, TaskPhase.CANCELLED, at(4), "user-1", "matrix"));

        assertEquals(TaskPhase.CANCELLED, cancelled.phase());
        assertTrue(cancelled.phase().terminal());
    }

    @Test
    void rejectsIllegalTransitionsWithTheCurrentAndRequestedPhases() {
        Task draft = Task.draft(UUID.randomUUID(), START);

        IllegalTaskTransitionException error = assertThrows(IllegalTaskTransitionException.class, () ->
                service.transition(draft, TaskTransitionCommand.simple(UUID.randomUUID(), draft.version(),
                        TaskPhase.RUNNING, at(1), "user-1", "api")));

        assertEquals(TaskPhase.DRAFT, error.currentPhase());
        assertEquals(TaskPhase.RUNNING, error.requestedPhase());
    }

    @Test
    void returnsDuplicateForTheSameEventIdWithoutAdvancingStateAgain() {
        Task draft = Task.draft(UUID.randomUUID(), START);
        UUID eventId = UUID.randomUUID();
        TaskTransitionCommand command = TaskTransitionCommand.simple(eventId, draft.version(),
                TaskPhase.QUEUED, at(1), "user-1", "api");

        TaskTransitionResult first = service.transition(draft, command);
        TaskTransitionResult second = service.transition(first.task(), command);

        assertTrue(second instanceof DuplicateTransition);
        assertEquals(first.task(), second.task());
        assertEquals(first.version(), second.version());
    }

    @Test
    void returnsDuplicateForAnOldEventAfterTheTaskHasAdvancedFurther() {
        Task draft = Task.draft(UUID.randomUUID(), START);
        UUID queuedEvent = UUID.randomUUID();
        Task queued = apply(draft, TaskTransitionCommand.simple(queuedEvent, draft.version(),
                TaskPhase.QUEUED, at(1), "user-1", "api"));
        Task assigned = apply(queued, TaskTransitionCommand.assign(UUID.randomUUID(), queued.version(),
                UUID.randomUUID(), UUID.randomUUID(), at(2), at(62), "scheduler", "scheduler"));

        TaskTransitionResult duplicate = service.transition(assigned, TaskTransitionCommand.simple(
                queuedEvent, 0, TaskPhase.QUEUED, at(99), "replayed", "replay"));

        assertTrue(duplicate instanceof DuplicateTransition);
        assertEquals(assigned, duplicate.task());
    }

    @Test
    void rejectsAStaleExpectedVersionBeforeApplyingAValidTransition() {
        Task queued = queuedTask();

        StaleTaskVersionException error = assertThrows(StaleTaskVersionException.class, () ->
                service.transition(queued, TaskTransitionCommand.simple(UUID.randomUUID(), 0,
                        TaskPhase.ASSIGNED, at(2), "scheduler", "scheduler")));

        assertEquals(0, error.expectedVersion());
        assertEquals(1, error.actualVersion());
        assertEquals(TaskPhase.QUEUED, queued.phase());
    }

    @Test
    void renewsOnlyAnActiveLeaseForTheMatchingAttemptAndExtendsTheExpiry() {
        Task task = assignedTask();
        UUID attemptId = task.attempt().id();
        UUID leaseId = task.attempt().leaseId();
        Instant renewedExpiry = at(90);

        TaskTransitionResult result = service.renewLease(task, new LeaseRenewalCommand(
                UUID.randomUUID(), task.version(), task.attempt().id(), task.attempt().leaseId(),
                at(30), renewedExpiry, "agent-1", "agent"));

        assertEquals(TaskPhase.ASSIGNED, result.task().phase());
        assertEquals(attemptId, result.task().attempt().id());
        assertEquals(leaseId, result.task().attempt().leaseId());
        assertEquals(renewedExpiry, result.task().attempt().leaseExpiresAt());
        assertEquals(3, result.version());
        assertVersionAligned(result.task());
    }

    @Test
    void rejectsHeartbeatForAnExpiredLease() {
        Task task = assignedTask();

        LeaseNotActiveException error = assertThrows(LeaseNotActiveException.class, () -> service.renewLease(task, new LeaseRenewalCommand(
                UUID.randomUUID(), task.version(), task.attempt().id(), task.attempt().leaseId(),
                at(62), at(90), "agent-1", "agent")));
        assertEquals(LeaseNotActiveReason.EXPIRED, error.reason());
    }

    @Test
    void rejectsHeartbeatForACompletedAttempt() {
        Task task = runningTask();
        Task completed = apply(task, execution(task, TaskPhase.SUCCEEDED, at(5)));

        LeaseNotActiveException error = assertThrows(LeaseNotActiveException.class, () -> service.renewLease(completed,
                new LeaseRenewalCommand(UUID.randomUUID(), completed.version(), completed.attempt().id(),
                        completed.attempt().leaseId(), at(6), at(90), "agent-1", "agent")));

        assertEquals(LeaseNotActiveReason.COMPLETED, error.reason());
    }

    @Test
    void treatsTheExactLeaseExpiryBoundaryAsExpired() {
        Task task = assignedTask();

        LeaseNotActiveException error = assertThrows(LeaseNotActiveException.class, () -> service.transition(
                task, new TaskTransitionCommand(UUID.randomUUID(), task.version(), TaskPhase.ACCEPTED,
                        task.attempt().id(), task.attempt().leaseId(), at(62), null, "agent-1", "agent", null)));

        assertEquals(LeaseNotActiveReason.EXPIRED, error.reason());
    }

    @Test
    void rejectsHeartbeatForAnotherAttemptOrLease() {
        Task task = assignedTask();

        LeaseNotActiveException attemptError = assertThrows(LeaseNotActiveException.class, () -> service.renewLease(task, new LeaseRenewalCommand(
                UUID.randomUUID(), task.version(), UUID.randomUUID(), task.attempt().leaseId(),
                at(30), at(90), "agent-1", "agent")));
        assertEquals(LeaseNotActiveReason.MISMATCH, attemptError.reason());
        LeaseNotActiveException leaseError = assertThrows(LeaseNotActiveException.class, () -> service.renewLease(task, new LeaseRenewalCommand(
                UUID.randomUUID(), task.version(), task.attempt().id(), UUID.randomUUID(),
                at(30), at(90), "agent-1", "agent")));
        assertEquals(LeaseNotActiveReason.MISMATCH, leaseError.reason());
    }

    @Test
    void rejectsAHeartbeatWithAnExpiryThatDoesNotMoveForward() {
        Task task = assignedTask();

        LeaseNotActiveException error = assertThrows(LeaseNotActiveException.class, () -> service.renewLease(task, new LeaseRenewalCommand(
                UUID.randomUUID(), task.version(), task.attempt().id(), task.attempt().leaseId(),
                at(30), at(60), "agent-1", "agent")));
        assertEquals(LeaseNotActiveReason.NOT_EXTENDING, error.reason());
    }

    @Test
    void cancelsAssignedTaskWithAnExpiredLeaseWithoutCheckingLeaseActivity() {
        Task assigned = assignedTask();

        Task cancelled = apply(assigned, TaskTransitionCommand.simple(UUID.randomUUID(), assigned.version(),
                TaskPhase.CANCELLED, at(2), "user-1", "api"));

        assertEquals(TaskPhase.CANCELLED, cancelled.phase());
        assertEquals(TaskPhase.CANCELLED, cancelled.attempt().phase());
    }

    @Test
    void rejectsMismatchedAttemptAndExpiredLeaseForExecutionTransitions() {
        Task task = assignedTask();
        TaskTransitionCommand mismatch = new TaskTransitionCommand(UUID.randomUUID(), task.version(),
                TaskPhase.ACCEPTED, UUID.randomUUID(), task.attempt().leaseId(), at(3), null,
                "agent-1", "agent", null);
        LeaseNotActiveException mismatchError = assertThrows(LeaseNotActiveException.class,
                () -> service.transition(task, mismatch));
        assertEquals(LeaseNotActiveReason.MISMATCH, mismatchError.reason());

        TaskTransitionCommand expired = new TaskTransitionCommand(UUID.randomUUID(), task.version(),
                TaskPhase.ACCEPTED, task.attempt().id(), task.attempt().leaseId(), at(62), null,
                "agent-1", "agent", null);
        LeaseNotActiveException expiredError = assertThrows(LeaseNotActiveException.class,
                () -> service.transition(task, expired));
        assertEquals(LeaseNotActiveReason.EXPIRED, expiredError.reason());
    }

    @Test
    void rejectsMissingFailureDetailsAndAccidentalFailureDetailsOnSuccess() {
        Task running = runningTask();
        TaskTransitionCommand missingFailure = new TaskTransitionCommand(UUID.randomUUID(), running.version(),
                TaskPhase.FAILED, running.attempt().id(), running.attempt().leaseId(), at(8), null,
                "agent-1", "agent", null);
        assertThrows(IllegalArgumentException.class, () -> service.transition(running, missingFailure));

        TaskTransitionCommand accidentalFailure = new TaskTransitionCommand(UUID.randomUUID(), running.version(),
                TaskPhase.SUCCEEDED, running.attempt().id(), running.attempt().leaseId(), at(8), null,
                "agent-1", "agent", FailureInfo.redacted("NOT_USED", "should not be stored"));
        assertThrows(IllegalArgumentException.class, () -> service.transition(running, accidentalFailure));
    }

    @Test
    void rejectsInvalidTaskAttemptConstruction() {
        UUID taskId = UUID.randomUUID();
        assertThrows(NullPointerException.class, () -> new TaskAttempt(null, taskId, UUID.randomUUID(),
                TaskPhase.ASSIGNED, START, START, at(1), null, "actor", "source", null, null, 1));
        assertThrows(IllegalArgumentException.class, () -> new TaskAttempt(UUID.randomUUID(), taskId,
                UUID.randomUUID(), TaskPhase.ASSIGNED, at(2), at(1), at(3), null, "actor", "source", null, null, 1));
        assertThrows(IllegalArgumentException.class, () -> new TaskAttempt(UUID.randomUUID(), taskId,
                UUID.randomUUID(), TaskPhase.ASSIGNED, START, START, at(1), null, "actor", "source", null, null, -1));
    }

    private Task runningTask() {
        Task task = acceptedTask();
        return apply(task, execution(task, TaskPhase.RUNNING, at(4)));
    }

    private Task failedTask() {
        Task task = runningTask();
        return apply(task, TaskTransitionCommand.failed(UUID.randomUUID(), task.version(),
                task.attempt().id(), task.attempt().leaseId(), at(8), "agent-1", "agent",
                FailureInfo.redacted("RUNTIME_ERROR", "execution failed")));
    }

    private Task acceptedTask() {
        Task task = assignedTask();
        return apply(task, execution(task, TaskPhase.ACCEPTED, at(3)));
    }

    private Task assignedTask() {
        Task task = queuedTask();
        return apply(task, TaskTransitionCommand.assign(UUID.randomUUID(), task.version(),
                UUID.randomUUID(), UUID.randomUUID(), at(2), at(62), "scheduler", "scheduler"));
    }

    private Task queuedTask() {
        Task task = Task.draft(UUID.randomUUID(), START);
        return apply(task, TaskTransitionCommand.simple(UUID.randomUUID(), task.version(),
                TaskPhase.QUEUED, at(1), "user-1", "api"));
    }

    private Task apply(Task task, TaskTransitionCommand command) {
        return service.transition(task, command).task();
    }

    private TaskTransitionCommand execution(Task task, TaskPhase phase, Instant at) {
        return TaskTransitionCommand.forAttempt(UUID.randomUUID(), task.version(), phase,
                task.attempt().id(), task.attempt().leaseId(), at, "agent-1", "agent");
    }

    private void assertVersionAligned(Task task) {
        if (task.attempt() != null) {
            assertEquals(task.version(), task.attempt().version());
        }
    }

    private Instant at(long secondsAfterStart) {
        return START.plusSeconds(secondsAfterStart);
    }
}
