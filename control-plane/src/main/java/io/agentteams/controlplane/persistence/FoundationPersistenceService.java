package io.agentteams.controlplane.persistence;

import io.agentteams.domain.agent.AgentPhase;
import io.agentteams.domain.task.TaskPhase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class FoundationPersistenceService {

    private static final String CREATE_TASK = "CREATE_TASK";
    private static final String CREATE_AGENT = "CREATE_AGENT";

    private final TransactionTemplate transactionTemplate;
    private final FoundationTransaction repositories;

    public FoundationPersistenceService(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repositories = new FoundationTransaction(jdbc);
    }

    public <T> T inTransaction(Function<FoundationTransaction, T> work) {
        Objects.requireNonNull(work, "work");
        T result = transactionTemplate.execute(status -> work.apply(repositories));
        return result;
    }

    public AgentRecord createAgent(AgentRecord agent) {
        return inTransaction(tx -> {
            tx.agents().insert(agent);
            appendEvent(tx, "agent", agent.id(), "AgentCreated", idPayload(agent.id()),
                    agent.updatedAt(), agent.version());
            return agent;
        });
    }

    public AgentRecord createAgent(AgentRecord agent, String idempotencyKey, String requestHash) {
        Objects.requireNonNull(agent, "agent");
        requireIdempotencyInput(idempotencyKey, requestHash);
        return inTransaction(tx -> {
            var existing = tx.idempotencyKeys().findByKey(idempotencyKey);
            if (existing.isPresent()) {
                assertIdempotency(existing.get(), CREATE_AGENT, requestHash, idempotencyKey);
                return tx.agents().findById(existing.get().resourceId())
                        .orElseThrow(() -> new IllegalStateException("idempotent agent is missing"));
            }

            IdempotencyKeyRecord keyRecord = new IdempotencyKeyRecord(UUID.randomUUID(), idempotencyKey,
                    CREATE_AGENT, requestHash, "agent", agent.id(), idPayload(agent.id()),
                    agent.createdAt(), agent.createdAt(), 0);
            if (!tx.idempotencyKeys().insertIfAbsent(keyRecord)) {
                IdempotencyKeyRecord winner = tx.idempotencyKeys().findByKey(idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException("idempotency key disappeared"));
                assertIdempotency(winner, CREATE_AGENT, requestHash, idempotencyKey);
                return tx.agents().findById(winner.resourceId())
                        .orElseThrow(() -> new IllegalStateException("idempotent agent is missing"));
            }
            tx.agents().insert(agent);
            appendEvent(tx, "agent", agent.id(), "AgentCreated", idPayload(agent.id()),
                    agent.updatedAt(), agent.version());
            return agent;
        });
    }

    public Optional<AgentRecord> findAgent(UUID id) {
        return inTransaction(tx -> tx.agents().findById(id));
    }

    public Optional<TaskRecord> findTask(UUID id) {
        return inTransaction(tx -> tx.tasks().findById(id));
    }

    public Optional<IdempotencyKeyRecord> findIdempotencyKey(String key) {
        requireKey(key);
        return inTransaction(tx -> tx.idempotencyKeys().findByKey(key));
    }

    public AgentRecord updateAgentPhase(UUID id, AgentPhase phase, long expectedVersion, Instant at) {
        return inTransaction(tx -> {
            AgentRecord updated = tx.agents().updatePhase(id, phase, expectedVersion, at);
            appendEvent(tx, "agent", id, "AgentPhaseChanged", idPayload(id), at, updated.version());
            return updated;
        });
    }

    public TaskRecord createTask(CreateTaskCommand command) {
        Objects.requireNonNull(command, "command");
        return inTransaction(tx -> {
            String requestHash = requestHash(command);
            var existing = tx.idempotencyKeys().findByKey(command.idempotencyKey());
            if (existing.isPresent()) {
                return existingTaskOrConflict(tx, existing.get(), requestHash, command.idempotencyKey());
            }

            UUID taskId = UUID.randomUUID();
            IdempotencyKeyRecord keyRecord = idempotencyRecord(command, requestHash, taskId);
            if (!tx.idempotencyKeys().insertIfAbsent(keyRecord)) {
                IdempotencyKeyRecord winner = tx.idempotencyKeys().findByKey(command.idempotencyKey())
                        .orElseThrow(() -> new IllegalStateException("idempotency key disappeared"));
                return existingTaskOrConflict(tx, winner, requestHash, command.idempotencyKey());
            }

            TaskRecord task = new TaskRecord(taskId, command.title(), command.description(), TaskPhase.DRAFT, 0,
                    command.specJson(), command.actor(), command.source(), null, null,
                    command.createdAt(), command.createdAt(), 0);
            tx.tasks().insert(task);
            appendEvent(tx, "task", task.id(), "TaskCreated", idPayload(task.id()),
                    task.createdAt(), task.version());
            return task;
        });
    }

    public TaskRecord updateTaskPhase(UUID id, TaskPhase phase, long expectedVersion, Instant at) {
        return inTransaction(tx -> {
            TaskRecord updated = tx.tasks().updatePhase(id, phase, expectedVersion, at);
            appendEvent(tx, "task", id, "TaskPhaseChanged", idPayload(id), at, updated.version());
            return updated;
        });
    }

    public TaskRecord transitionTask(UUID id, TaskPhase phase, long expectedVersion, Instant at,
            String idempotencyKey, String requestHash, String operation) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(at, "at");
        requireIdempotencyInput(idempotencyKey, requestHash);
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        return inTransaction(tx -> {
            var existing = tx.idempotencyKeys().findByKey(idempotencyKey);
            if (existing.isPresent()) {
                assertIdempotency(existing.get(), operation, requestHash, idempotencyKey);
                return tx.tasks().findById(existing.get().resourceId())
                        .orElseThrow(() -> new IllegalStateException("idempotent task is missing"));
            }
            if (tx.tasks().findById(id).isEmpty()) {
                throw new IllegalArgumentException("task does not exist");
            }

            IdempotencyKeyRecord keyRecord = new IdempotencyKeyRecord(UUID.randomUUID(), idempotencyKey,
                    operation, requestHash, "task", id, idPayload(id), at, at, 0);
            if (!tx.idempotencyKeys().insertIfAbsent(keyRecord)) {
                IdempotencyKeyRecord winner = tx.idempotencyKeys().findByKey(idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException("idempotency key disappeared"));
                assertIdempotency(winner, operation, requestHash, idempotencyKey);
                return tx.tasks().findById(winner.resourceId())
                        .orElseThrow(() -> new IllegalStateException("idempotent task is missing"));
            }

            TaskRecord updated = tx.tasks().updatePhase(id, phase, expectedVersion, at);
            appendEvent(tx, "task", id, "TaskPhaseChanged", idPayload(id), at, updated.version());
            return updated;
        });
    }

    public TaskRecord transitionTaskWithSpec(UUID id, TaskPhase phase, String specJson, long expectedVersion,
            Instant at, String idempotencyKey, String requestHash, String operation, String approvalStatus) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(specJson, "specJson");
        Objects.requireNonNull(at, "at");
        requireIdempotencyInput(idempotencyKey, requestHash);
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        return inTransaction(tx -> {
            var existing = tx.idempotencyKeys().findByKey(idempotencyKey);
            if (existing.isPresent()) {
                assertIdempotency(existing.get(), operation, requestHash, idempotencyKey);
                return tx.tasks().findById(existing.get().resourceId())
                        .orElseThrow(() -> new IllegalStateException("idempotent task is missing"));
            }

            TaskRecord current = tx.tasks().findByIdForUpdate(id)
                    .orElseThrow(() -> new IllegalArgumentException("task does not exist: " + id));
            IdempotencyKeyRecord keyRecord = new IdempotencyKeyRecord(UUID.randomUUID(), idempotencyKey,
                    operation, requestHash, "task", id, idPayload(id), at, at, 0);
            if (!tx.idempotencyKeys().insertIfAbsent(keyRecord)) {
                IdempotencyKeyRecord winner = tx.idempotencyKeys().findByKey(idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException("idempotency key disappeared"));
                assertIdempotency(winner, operation, requestHash, idempotencyKey);
                return tx.tasks().findById(winner.resourceId())
                        .orElseThrow(() -> new IllegalStateException("idempotent task is missing"));
            }

            TaskRecord next = new TaskRecord(current.id(), current.title(), current.description(), phase,
                    current.priority(), specJson, current.actor(), current.source(),
                    phase == TaskPhase.FAILED ? current.failureCode() : null,
                    phase == TaskPhase.FAILED ? current.redactedFailureMessage() : null,
                    current.createdAt(), at, current.version());
            TaskRecord updated = tx.tasks().updateState(next, expectedVersion);
            if (approvalStatus != null) {
                tx.teams().updateApprovalStatus(id, approvalStatus, at);
            }
            appendEvent(tx, "task", id, "TaskStateChanged",
                    "{\"id\":\"" + id + "\",\"phase\":\"" + phase
                            + "\",\"approvalStatus\":" + (approvalStatus == null ? "null" : "\"" + approvalStatus + "\"") + "}",
                    at, updated.version());
            return updated;
        });
    }

    public UUID createFoundation(AgentRecord agent, TaskRecord task, TaskAttemptRecord attempt,
            TaskAssignmentRecord assignment, AgentLeaseRecord lease, Instant occurredAt) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(lease, "lease");
        return inTransaction(tx -> {
            tx.agents().insert(agent);
            tx.tasks().insert(task);
            tx.taskAttempts().insert(attempt);
            tx.taskAssignments().insert(assignment);
            tx.agentLeases().insert(lease);
            appendEvent(tx, "agent", agent.id(), "AgentCreated", idPayload(agent.id()),
                    occurredAt, agent.version());
            UUID taskEventId = appendEvent(tx, "task", task.id(), "TaskCreated", idPayload(task.id()),
                    occurredAt, task.version());
            appendEvent(tx, "task_attempt", attempt.id(), "TaskAttemptCreated", idPayload(attempt.id()),
                    occurredAt, attempt.version());
            appendEvent(tx, "task_assignment", assignment.id(), "TaskAssignmentCreated",
                    idPayload(assignment.id()), occurredAt, assignment.version());
            appendEvent(tx, "agent_lease", lease.id(), "AgentLeaseCreated", idPayload(lease.id()),
                    occurredAt, lease.version());
            return taskEventId;
        });
    }

    public TaskAttemptRecord updateAttemptPhase(UUID id, TaskPhase phase, Instant completedAt,
            String failureCode, String redactedFailureMessage, long expectedVersion, Instant at) {
        return inTransaction(tx -> {
            TaskAttemptRecord updated = tx.taskAttempts().updatePhase(id, phase, completedAt,
                    failureCode, redactedFailureMessage, expectedVersion, at);
            appendEvent(tx, "task_attempt", id, "TaskAttemptPhaseChanged", idPayload(id), at, updated.version());
            return updated;
        });
    }

    public TaskAssignmentRecord updateAssignmentPhase(UUID id, TaskPhase phase,
            long expectedVersion, Instant at) {
        return inTransaction(tx -> {
            TaskAssignmentRecord updated = tx.taskAssignments().updatePhase(id, phase, expectedVersion, at);
            appendEvent(tx, "task_assignment", id, "TaskAssignmentPhaseChanged", idPayload(id), at,
                    updated.version());
            return updated;
        });
    }

    public AgentLeaseRecord updateLeaseStatus(UUID id, String status, Instant releasedAt,
            long expectedVersion, Instant at) {
        return inTransaction(tx -> {
            AgentLeaseRecord updated = tx.agentLeases().updateStatus(id, status, releasedAt, expectedVersion, at);
            appendEvent(tx, "agent_lease", id, "AgentLeaseStatusChanged", idPayload(id), at, updated.version());
            return updated;
        });
    }

    private static TaskRecord existingTaskOrConflict(FoundationTransaction tx, IdempotencyKeyRecord existing,
            String requestHash, String key) {
        if (!CREATE_TASK.equals(existing.operation()) || !existing.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(key, CREATE_TASK);
        }
        return tx.tasks().findById(existing.resourceId())
                .orElseThrow(() -> new IllegalStateException("idempotent task is missing"));
    }

    private static IdempotencyKeyRecord idempotencyRecord(CreateTaskCommand command,
            String requestHash, UUID taskId) {
        return new IdempotencyKeyRecord(UUID.randomUUID(), command.idempotencyKey(), CREATE_TASK, requestHash,
                "task", taskId, idPayload(taskId), command.createdAt(), command.createdAt(), 0);
    }

    public static UUID appendEvent(FoundationTransaction tx, String aggregateType, UUID aggregateId,
            String eventType, String payloadJson, Instant occurredAt, long aggregateVersion) {
        return appendEvent(tx, UUID.randomUUID(), aggregateType, aggregateId, eventType, payloadJson,
                occurredAt, aggregateVersion);
    }

    public static UUID appendEvent(FoundationTransaction tx, UUID eventId, String aggregateType,
            UUID aggregateId, String eventType, String payloadJson, Instant occurredAt, long aggregateVersion) {
        Objects.requireNonNull(eventId, "eventId");
        DomainEventRecord domainEvent = DomainEventRecord.create(eventId, aggregateType, aggregateId,
                eventType, payloadJson, occurredAt, aggregateVersion);
        tx.domainEvents().insert(domainEvent);
        tx.outboxEvents().insert(OutboxEventRecord.pending(eventId, aggregateType, aggregateId,
                eventType, payloadJson, aggregateVersion, occurredAt, occurredAt, currentTraceContext()));
        return eventId;
    }

    private static io.agentteams.application.api.TraceContext currentTraceContext() {
        String correlationId = MDC.get("correlationId");
        String traceId = MDC.get("traceId");
        String spanId = MDC.get("spanId");
        String traceparent = "";
        if (traceId != null && traceId.matches("[0-9a-fA-F]{32}")
                && spanId != null && spanId.matches("[0-9a-fA-F]{16}")) {
            traceparent = "00-" + traceId.toLowerCase(java.util.Locale.ROOT) + "-"
                    + spanId.toLowerCase(java.util.Locale.ROOT) + "-01";
        }
        return new io.agentteams.application.api.TraceContext(correlationId, traceparent, MDC.get("tracestate"));
    }

    private static String idPayload(UUID id) {
        return "{\"id\":\"" + id + "\"}";
    }

    private static String requestHash(CreateTaskCommand command) {
        String input = String.join("\u001f", command.title(), command.description(), command.actor(),
                command.source(), command.specJson());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void requireIdempotencyInput(String key, String requestHash) {
        requireKey(key);
        if (requestHash == null || requestHash.isBlank()) {
            throw new IllegalArgumentException("requestHash must not be blank");
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
    }

    private static void assertIdempotency(IdempotencyKeyRecord existing, String operation,
            String requestHash, String key) {
        if (!operation.equals(existing.operation()) || !requestHash.equals(existing.requestHash())) {
            throw new IdempotencyConflictException(key, operation);
        }
    }
}
