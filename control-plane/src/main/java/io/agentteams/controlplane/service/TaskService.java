package io.agentteams.controlplane.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.controlplane.persistence.CreateTaskCommand;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.observability.TaskMetricsPort;
import io.agentteams.domain.task.Task;
import io.agentteams.domain.task.IllegalTaskTransitionException;
import io.agentteams.domain.task.TaskPhase;
import io.agentteams.domain.task.TaskTransitionCommand;
import io.agentteams.domain.task.TaskTransitionService;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public final class TaskService {

    private static final String QUEUE_TASK = "QUEUE_TASK";
    private static final String CANCEL_TASK = "CANCEL_TASK";
    private static final String RETRY_TASK = "RETRY_TASK";
    private static final String PAUSE_TASK = "PAUSE_TASK";
    private static final String APPROVE_TASK = "APPROVE_TASK";
    private static final String REJECT_TASK = "REJECT_TASK";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FoundationPersistenceService persistence;
    private final IdempotencyService idempotency;
    private final TaskTransitionService transitions;
    private final Clock clock;
    private final TaskMetricsPort metrics;

    public TaskService(FoundationPersistenceService persistence, IdempotencyService idempotency,
            TaskTransitionService transitions) {
        this(persistence, idempotency, transitions, Clock.systemUTC(), TaskMetricsPort.noop());
    }

    @Autowired
    public TaskService(FoundationPersistenceService persistence, IdempotencyService idempotency,
            TaskTransitionService transitions, TaskMetricsPort metrics) {
        this(persistence, idempotency, transitions, Clock.systemUTC(), metrics);
    }

    TaskService(FoundationPersistenceService persistence, IdempotencyService idempotency,
            TaskTransitionService transitions, Clock clock) {
        this(persistence, idempotency, transitions, clock, TaskMetricsPort.noop());
    }

    TaskService(FoundationPersistenceService persistence, IdempotencyService idempotency,
            TaskTransitionService transitions, Clock clock, TaskMetricsPort metrics) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public TaskRecord create(String idempotencyKey, TaskInput input) {
        Objects.requireNonNull(input, "input");
        String key = idempotency.requireKey(idempotencyKey);
        String title = required(input.title(), "title");
        String description = input.description() == null ? "" : input.description();
        String actor = defaultText(input.actor(), "api");
        String source = defaultText(input.source(), "rest");
        String spec = jsonObjectOrDefault(input.specJson());
        Instant now = clock.instant();
        TaskRecord created = persistence.createTask(new CreateTaskCommand(key, title, description, actor, source, spec, now));
        metrics.taskCreated();
        return created;
    }

    public TaskRecord get(UUID id) {
        Objects.requireNonNull(id, "id");
        return persistence.findTask(id).orElseThrow(() -> new ResourceNotFoundException("task", id));
    }

    /** Explicit queue operation; task creation intentionally remains in DRAFT. */
    public TaskRecord queue(UUID id, long expectedVersion, String idempotencyKey) {
        return queue(id, expectedVersion, idempotencyKey, "api");
    }

    public TaskRecord queue(UUID id, long expectedVersion, String idempotencyKey, String actor) {
        return transition(id, TaskPhase.QUEUED, expectedVersion, idempotencyKey,
                defaultText(actor, "api"), "service", QUEUE_TASK);
    }

    public TaskRecord cancel(UUID id, long expectedVersion, String idempotencyKey,
            String actor, String source) {
        return transition(id, TaskPhase.CANCELLED, expectedVersion, idempotencyKey,
                defaultText(actor, "api"), defaultText(source, "rest"), CANCEL_TASK);
    }

    public TaskRecord retry(UUID id, long expectedVersion, String idempotencyKey,
            String actor, String source) {
        return transition(id, TaskPhase.QUEUED, expectedVersion, idempotencyKey,
                defaultText(actor, "api"), defaultText(source, "rest"), RETRY_TASK);
    }

    /** Pause toggles QUEUED to PAUSED and PAUSED back to QUEUED. */
    public TaskRecord pause(UUID id, long expectedVersion, String idempotencyKey,
            String actor, String source) {
        TaskRecord current = get(id);
        TaskPhase target = current.phase() == TaskPhase.PAUSED ? TaskPhase.QUEUED : TaskPhase.PAUSED;
        return transition(id, target, expectedVersion, idempotencyKey,
                defaultText(actor, "api"), defaultText(source, "rest"), PAUSE_TASK);
    }

    public TaskRecord approve(UUID id, long expectedVersion, String idempotencyKey,
            String actor, String source) {
        return updateApproval(id, expectedVersion, idempotencyKey,
                defaultText(actor, "api"), defaultText(source, "rest"), true, APPROVE_TASK);
    }

    public TaskRecord reject(UUID id, long expectedVersion, String idempotencyKey,
            String actor, String source) {
        return updateApproval(id, expectedVersion, idempotencyKey,
                defaultText(actor, "api"), defaultText(source, "rest"), false, REJECT_TASK);
    }

    public record TaskInput(String title, String description, String specJson, String actor, String source) {
    }

    private TaskRecord transition(UUID id, TaskPhase target, long expectedVersion, String idempotencyKey,
            String actor, String source, String operation) {
        Objects.requireNonNull(id, "id");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        String key = idempotency.requireKey(idempotencyKey);
        Instant now = clock.instant();
        String requestHash = idempotency.requestHash(id.toString(), target.name(), Long.toString(expectedVersion),
                actor, source);
        if (persistence.findIdempotencyKey(key).isPresent()) {
            return persistence.transitionTask(id, target, expectedVersion, now, key, requestHash, operation);
        }

        TaskRecord current = get(id);
        Task domainTask = new Task(current.id(), current.phase(), current.version(), null,
                current.createdAt(), current.updatedAt(), current.actor(), current.source(),
                current.failureCode(), current.redactedFailureMessage(), Set.of());
        try {
            transitions.transition(domainTask, TaskTransitionCommand.simple(UUID.randomUUID(), expectedVersion,
                    target, now, actor, source));
        } catch (IllegalTaskTransitionException error) {
            if (persistence.findIdempotencyKey(key).isPresent()) {
                return persistence.transitionTask(id, target, expectedVersion, now, key, requestHash, operation);
            }
            throw error;
        }
        return persistence.transitionTask(id, target, expectedVersion, now, key, requestHash, operation);
    }

    private TaskRecord updateApproval(UUID id, long expectedVersion, String idempotencyKey,
            String actor, String source, boolean granted, String operation) {
        Objects.requireNonNull(id, "id");
        TaskRecord current = get(id);
        if (current.phase() != TaskPhase.DRAFT && current.phase() != TaskPhase.QUEUED
                && current.phase() != TaskPhase.PAUSED) {
            throw new IllegalArgumentException("task approval is only available before assignment");
        }
        TaskPhase target = operation.equals(REJECT_TASK) ? TaskPhase.REJECTED : current.phase();
        ObjectNode spec = approvalSpec(current.specJson(), granted);
        String specJson;
        try {
            specJson = JSON.writeValueAsString(spec);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("task approval spec could not be encoded", error);
        }
        String key = idempotency.requireKey(idempotencyKey);
        String requestHash = idempotency.requestHash(id.toString(), target.name(), Long.toString(expectedVersion),
                specJson, actor, source, Boolean.toString(granted));
        return persistence.transitionTaskWithSpec(id, target, specJson, expectedVersion, clock.instant(), key,
                requestHash, operation, granted ? "APPROVED" : "REJECTED");
    }

    private static ObjectNode approvalSpec(String specJson, boolean granted) {
        try {
            JsonNode root = JSON.readTree(specJson == null ? "{}" : specJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("task spec must be a JSON object");
            }
            ObjectNode object = (ObjectNode) root;
            object.put("approvalGranted", granted);
            return object;
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("task spec is invalid JSON", error);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String jsonObjectOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return "{}";
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new IllegalArgumentException("JSON object is required");
        }
        return trimmed;
    }
}
