package io.agentteams.controlplane.service;

import io.agentteams.controlplane.persistence.ArtifactRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.FoundationTransaction;
import io.agentteams.controlplane.persistence.TaskAttemptRecord;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.observability.TaskMetricsPort;
import io.agentteams.domain.task.AppliedTransition;
import io.agentteams.domain.task.DuplicateTransition;
import io.agentteams.domain.task.Task;
import io.agentteams.domain.task.TaskAttempt;
import io.agentteams.domain.task.LeaseRenewalCommand;
import io.agentteams.domain.task.TaskTransitionCommand;
import io.agentteams.domain.task.TaskTransitionResult;
import io.agentteams.domain.task.TaskTransitionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ExecutionEventService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final FoundationPersistenceService persistence;
    private final TaskTransitionService transitions;
    private final TaskMetricsPort metrics;

    public ExecutionEventService(FoundationPersistenceService persistence) {
        this(persistence, new TaskTransitionService(), TaskMetricsPort.noop());
    }

    ExecutionEventService(FoundationPersistenceService persistence, TaskTransitionService transitions) {
        this(persistence, transitions, TaskMetricsPort.noop());
    }

    public ExecutionEventService(FoundationPersistenceService persistence, TaskMetricsPort metrics) {
        this(persistence, new TaskTransitionService(), metrics);
    }

    ExecutionEventService(FoundationPersistenceService persistence, TaskTransitionService transitions,
            TaskMetricsPort metrics) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public TaskTransitionResult apply(UUID taskId, TaskTransitionCommand command,
            List<ArtifactRecord> artifacts) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(artifacts, "artifacts");
        TaskTransitionResult result = persistence.inTransaction(tx -> applyInTransaction(tx, taskId, command, artifacts));
        if (result instanceof AppliedTransition applied && applied.task().phase().terminal()) {
            if (applied.task().phase() == io.agentteams.domain.task.TaskPhase.SUCCEEDED) {
                metrics.taskCompleted();
            } else {
                metrics.taskFailed();
            }
        }
        return result;
    }

    public TaskTransitionResult renewLease(UUID taskId, LeaseRenewalCommand command) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(command, "command");
        return persistence.inTransaction(tx -> {
            TaskRecord current = tx.tasks().findById(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("task does not exist: " + taskId));
            TaskAttemptRecord currentAttempt = tx.taskAttempts().findById(command.attemptId()).orElseThrow();
            var currentLease = tx.agentLeases().findById(command.leaseId()).orElseThrow();
            Task domain = toDomain(current, currentAttempt, Set.of());
            if (tx.domainEvents().findByEventId(command.eventId()).isPresent()) {
                return new DuplicateTransition(command.eventId(), domain);
            }
            TaskTransitionResult result = transitions.renewLease(domain, command);
            if (result instanceof DuplicateTransition) {
                return result;
            }
            AppliedTransition applied = (AppliedTransition) result;
            Task next = applied.task();
            TaskRecord nextRecord = new TaskRecord(current.id(), current.title(), current.description(), next.phase(),
                    current.priority(), current.specJson(), next.actor(), next.source(), current.failureCode(),
                    current.redactedFailureMessage(), current.createdAt(), next.updatedAt(), next.version());
            tx.tasks().updateState(nextRecord, current.version());
            TaskAttemptRecord nextAttempt = TaskAttemptRecord.fromDomain(next.attempt());
            tx.taskAttempts().updateLease(nextAttempt.id(), nextAttempt.leaseExpiresAt(), nextAttempt.actor(),
                    nextAttempt.source(), currentAttempt.version(), nextAttempt.updatedAt());
            tx.agentLeases().updateExpiry(nextAttempt.leaseId(), nextAttempt.leaseExpiresAt(),
                    currentLease.version(), nextAttempt.updatedAt());
            FoundationPersistenceService.appendEvent(tx, command.eventId(), "task", taskId, "TaskLeaseRenewed",
                    "{\"eventId\":\"" + command.eventId() + "\",\"leaseExpiresAt\":\""
                            + command.requestedExpiry() + "\"}", command.occurredAt(), next.version());
            return result;
        });
    }

    private TaskTransitionResult applyInTransaction(FoundationTransaction tx, UUID taskId,
            TaskTransitionCommand command, List<ArtifactRecord> artifacts) {
        TaskRecord current = tx.tasks().findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("task does not exist: " + taskId));
        TaskAttemptRecord currentAttempt = command.attemptId() == null ? null
                : tx.taskAttempts().findById(command.attemptId()).orElseThrow();
        Task domain = toDomain(current, currentAttempt, Set.of());

        if (tx.domainEvents().findByEventId(command.eventId()).isPresent()) {
            return new DuplicateTransition(command.eventId(), domain);
        }

        TaskTransitionResult result = transitions.transition(domain, command);
        if (result instanceof DuplicateTransition) {
            return result;
        }
        AppliedTransition applied = (AppliedTransition) result;
        Task next = applied.task();
        TaskRecord nextRecord = new TaskRecord(current.id(), current.title(), current.description(), next.phase(),
                current.priority(), current.specJson(), next.actor(), next.source(), next.failureCode(),
                next.redactedFailureMessage(), current.createdAt(), next.updatedAt(), next.version());
        tx.tasks().updateState(nextRecord, current.version());

        if (next.attempt() != null) {
            TaskAttemptRecord previousAttempt = currentAttempt;
            TaskAttemptRecord nextAttempt = TaskAttemptRecord.fromDomain(next.attempt());
            tx.taskAttempts().updatePhase(nextAttempt.id(), nextAttempt.phase(), nextAttempt.completedAt(),
                    nextAttempt.failureCode(), nextAttempt.redactedFailureMessage(), previousAttempt.version(),
                    nextAttempt.updatedAt());
        }
        if (next.phase().terminal()) {
            teamId(current).ifPresent(team -> tx.teams().releaseTaskAssignment(team, taskId, next.updatedAt()));
        }
        for (ArtifactRecord artifact : artifacts) {
            if (!taskId.equals(artifact.taskId())) {
                throw new IllegalArgumentException("artifact belongs to another task");
            }
            if (next.attempt() == null || !next.attempt().id().equals(artifact.attemptId())) {
                throw new IllegalArgumentException("artifact must belong to the transitioned attempt");
            }
            tx.artifacts().insertIfAbsent(artifact);
        }
        FoundationPersistenceService.appendEvent(tx, command.eventId(), "task", taskId, "TaskTransitionApplied",
                transitionPayload(command, applied), command.occurredAt(), next.version());
        return result;
    }

    private static Task toDomain(TaskRecord task, TaskAttemptRecord attempt, Set<UUID> processedEvents) {
        TaskAttempt domainAttempt = attempt == null ? null : new TaskAttempt(attempt.id(), attempt.taskId(),
                attempt.leaseId(), attempt.phase(), attempt.createdAt(), attempt.updatedAt(),
                attempt.leaseExpiresAt(), attempt.completedAt(), attempt.actor(), attempt.source(),
                attempt.failureCode(), attempt.redactedFailureMessage(), attempt.version());
        return new Task(task.id(), task.phase(), task.version(), domainAttempt, task.createdAt(), task.updatedAt(),
                task.actor(), task.source(), task.failureCode(), task.redactedFailureMessage(), processedEvents);
    }

    private static String transitionPayload(TaskTransitionCommand command, AppliedTransition transition) {
        return "{\"eventId\":\"" + command.eventId() + "\",\"taskId\":\""
                + transition.task().id() + "\",\"from\":\"" + transition.fromPhase()
                + "\",\"to\":\"" + transition.toPhase() + "\",\"occurredAt\":\""
                + command.occurredAt() + "\"}";
    }

    private static java.util.Optional<UUID> teamId(TaskRecord task) {
        try {
            JsonNode spec = OBJECT_MAPPER.readTree(task.specJson());
            JsonNode team = spec == null ? null : spec.get("teamId");
            return team != null && team.isTextual() && !team.asText().isBlank()
                    ? java.util.Optional.of(UUID.fromString(team.asText())) : java.util.Optional.empty();
        } catch (Exception error) {
            throw new IllegalArgumentException("task spec cannot be parsed for team assignment", error);
        }
    }
}
