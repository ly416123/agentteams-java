package io.agentteams.controlplane.persistence;

import io.agentteams.domain.agent.AgentPhase;
import io.agentteams.domain.task.TaskPhase;
import io.agentteams.controlplane.service.ModelCatalogDependencyException;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
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
    private static final String CREATE_TEAM = "CREATE_TEAM";
    private static final String CREATE_MODEL_PROVIDER = "CREATE_MODEL_PROVIDER";
    private static final String CREATE_MODEL = "CREATE_MODEL";
    private static final String CREATE_MODEL_PRICE = "CREATE_MODEL_PRICE";

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

    public TeamRecord createTeam(TeamRecord team, TeamPolicyRecord policy, String idempotencyKey,
            String requestHash) {
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(policy, "policy");
        if (!team.id().equals(policy.teamId())) {
            throw new IllegalArgumentException("team policy must reference the team being created");
        }
        requireIdempotencyInput(idempotencyKey, requestHash);
        return inTransaction(tx -> {
            var existing = tx.idempotencyKeys().findByKey(idempotencyKey);
            if (existing.isPresent()) {
                assertIdempotency(existing.get(), CREATE_TEAM, requestHash, idempotencyKey);
                return tx.teams().findById(existing.get().resourceId())
                        .orElseThrow(() -> new IllegalStateException("idempotent team is missing"));
            }
            IdempotencyKeyRecord keyRecord = new IdempotencyKeyRecord(UUID.randomUUID(), idempotencyKey,
                    CREATE_TEAM, requestHash, "team", team.id(), idPayload(team.id()),
                    team.createdAt(), team.createdAt(), 0);
            if (!tx.idempotencyKeys().insertIfAbsent(keyRecord)) {
                IdempotencyKeyRecord winner = tx.idempotencyKeys().findByKey(idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException("idempotency key disappeared"));
                assertIdempotency(winner, CREATE_TEAM, requestHash, idempotencyKey);
                return tx.teams().findById(winner.resourceId())
                        .orElseThrow(() -> new IllegalStateException("idempotent team is missing"));
            }
            tx.teams().insert(team);
            tx.teams().insertPolicy(policy);
            appendEvent(tx, "team", team.id(), "TeamCreated", idPayload(team.id()), team.createdAt(), team.version());
            return team;
        });
    }

    public ModelProviderRecord createModelProvider(ModelProviderRecord provider, String idempotencyKey,
            String requestHash) {
        Objects.requireNonNull(provider, "provider");
        requireIdempotencyInput(idempotencyKey, requestHash);
        return inTransaction(tx -> {
            var existing = tx.idempotencyKeys().findByKey(idempotencyKey);
            if (existing.isPresent()) {
                assertIdempotency(existing.get(), CREATE_MODEL_PROVIDER, requestHash, idempotencyKey);
                return tx.modelProviders().findById(existing.get().resourceId())
                        .orElseThrow(() -> new IllegalStateException("idempotent model provider is missing"));
            }
            IdempotencyKeyRecord keyRecord = new IdempotencyKeyRecord(UUID.randomUUID(), idempotencyKey,
                    CREATE_MODEL_PROVIDER, requestHash, "model_provider", provider.id(), idPayload(provider.id()),
                    provider.createdAt(), provider.createdAt(), 0);
            if (!tx.idempotencyKeys().insertIfAbsent(keyRecord)) {
                IdempotencyKeyRecord winner = tx.idempotencyKeys().findByKey(idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException("idempotency key disappeared"));
                assertIdempotency(winner, CREATE_MODEL_PROVIDER, requestHash, idempotencyKey);
                return tx.modelProviders().findById(winner.resourceId())
                        .orElseThrow(() -> new IllegalStateException("idempotent model provider is missing"));
            }
            tx.modelProviders().insert(provider);
            appendEvent(tx, "model_provider", provider.id(), "ModelProviderCreated", idPayload(provider.id()),
                    provider.updatedAt(), provider.version());
            return provider;
        });
    }

    public Optional<ModelProviderRecord> findModelProvider(UUID id) {
        return inTransaction(tx -> tx.modelProviders().findById(id));
    }

    public List<ModelProviderRecord> findModelProviders() {
        return inTransaction(tx -> tx.modelProviders().findAll());
    }

    public ModelRecord createModel(ModelRecord model, String idempotencyKey, String requestHash) {
        Objects.requireNonNull(model, "model");
        requireIdempotencyInput(idempotencyKey, requestHash);
        return inTransaction(tx -> {
            if (tx.modelProviders().findById(model.providerId()).isEmpty()) {
                throw new IllegalArgumentException("model provider does not exist: " + model.providerId());
            }
            var existing = tx.idempotencyKeys().findByKey(idempotencyKey);
            if (existing.isPresent()) {
                assertIdempotency(existing.get(), CREATE_MODEL, requestHash, idempotencyKey);
                return tx.models().findById(existing.get().resourceId())
                        .orElseThrow(() -> new IllegalStateException("idempotent model is missing"));
            }
            IdempotencyKeyRecord keyRecord = new IdempotencyKeyRecord(UUID.randomUUID(), idempotencyKey,
                    CREATE_MODEL, requestHash, "model", model.id(), idPayload(model.id()),
                    model.createdAt(), model.createdAt(), 0);
            if (!tx.idempotencyKeys().insertIfAbsent(keyRecord)) {
                IdempotencyKeyRecord winner = tx.idempotencyKeys().findByKey(idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException("idempotency key disappeared"));
                assertIdempotency(winner, CREATE_MODEL, requestHash, idempotencyKey);
                return tx.models().findById(winner.resourceId())
                        .orElseThrow(() -> new IllegalStateException("idempotent model is missing"));
            }
            tx.models().insert(model);
            appendEvent(tx, "model", model.id(), "ModelCreated", idPayload(model.id()),
                    model.updatedAt(), model.version());
            return model;
        });
    }

    public Optional<ModelRecord> findModel(UUID id) {
        return inTransaction(tx -> tx.models().findById(id));
    }

    public List<ModelRecord> findModelsByProvider(UUID providerId) {
        return inTransaction(tx -> tx.models().findByProviderId(providerId));
    }

    public ModelPriceRecord createModelPrice(ModelPriceRecord price, String idempotencyKey,
            String requestHash) {
        Objects.requireNonNull(price, "price");
        requireIdempotencyInput(idempotencyKey, requestHash);
        return inTransaction(tx -> {
            var existing = tx.modelPrices().findIdempotency(price.tenantId(), price.projectId(), idempotencyKey);
            if (existing.isPresent()) {
                assertPriceIdempotency(existing.get(), requestHash, idempotencyKey);
                return tx.modelPrices().findById(existing.get().priceId(), price.tenantId(), price.projectId())
                        .orElseThrow(() -> new IllegalStateException("idempotent model price is missing"));
            }
            if (!tx.modelPrices().insertIdempotency(price.tenantId(), price.projectId(), idempotencyKey,
                    requestHash, price.id(), price.createdAt())) {
                var winner = tx.modelPrices().findIdempotency(price.tenantId(), price.projectId(), idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException("model price idempotency key disappeared"));
                assertPriceIdempotency(winner, requestHash, idempotencyKey);
                return tx.modelPrices().findById(winner.priceId(), price.tenantId(), price.projectId())
                        .orElseThrow(() -> new IllegalStateException("idempotent model price is missing"));
            }
            tx.modelPrices().insert(price);
            appendEvent(tx, "model_price", price.id(), "ModelPriceCreated", idPayload(price.id()),
                    price.updatedAt(), price.version());
            return price;
        });
    }

    public Optional<ModelPriceRecord> findModelPrice(UUID id, String tenantId, String projectId) {
        return inTransaction(tx -> tx.modelPrices().findById(id, tenantId, projectId));
    }

    public List<ModelPriceRecord> findModelPrices(String tenantId, String projectId) {
        return inTransaction(tx -> tx.modelPrices().findAll(tenantId, projectId));
    }

    public Optional<ModelPriceRecord> findEffectiveModelPrice(String tenantId, String projectId,
            String provider, String model, String currency, Instant at) {
        return inTransaction(tx -> tx.modelPrices().findEffective(tenantId, projectId, provider, model,
                currency, at));
    }

    public ModelPriceRecord updateModelPriceLifecycle(UUID id, String tenantId, String projectId,
            String lifecycleStatus, Instant at, String updatedBy) {
        return inTransaction(tx -> {
            ModelPriceRecord current = tx.modelPrices().findById(id, tenantId, projectId)
                    .orElseThrow(() -> new ResourceNotFoundException("model price", id));
            if (current.lifecycleStatus().equals(lifecycleStatus)) {
                return current;
            }
            ModelPriceRecord updated = tx.modelPrices().updateLifecycle(id, tenantId, projectId,
                    lifecycleStatus, current.version(), at, updatedBy);
            appendEvent(tx, "model_price", id, "ModelPriceLifecycleChanged", idPayload(id), at,
                    updated.version());
            return updated;
        });
    }

    public ModelProviderRecord updateModelProviderEnabled(UUID id, boolean enabled, Instant at) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(at, "at");
        return inTransaction(tx -> {
            ModelProviderRecord current = tx.modelProviders().findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("model provider", id));
            if (!enabled && current.enabled()
                    && tx.modelProviders().countActiveAgentSpecReferences(current.name()) > 0) {
                throw new ModelCatalogDependencyException("MODEL_PROVIDER_IN_USE",
                        "model provider is referenced by an active agent spec");
            }
            if (current.enabled() == enabled) {
                return current;
            }
            ModelProviderRecord updated = tx.modelProviders().updateEnabled(id, enabled, current.version(), at);
            appendEvent(tx, "model_provider", id, "ModelProviderEnabledChanged", idPayload(id), at,
                    updated.version());
            return updated;
        });
    }

    public void deleteModelProvider(UUID id) {
        Objects.requireNonNull(id, "id");
        inTransaction(tx -> {
            ModelProviderRecord current = tx.modelProviders().findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("model provider", id));
            if (tx.modelProviders().countActiveAgentSpecReferences(current.name()) > 0) {
                throw new ModelCatalogDependencyException("MODEL_PROVIDER_IN_USE",
                        "model provider is referenced by an active agent spec");
            }
            if (tx.modelProviders().countModels(id) > 0) {
                throw new ModelCatalogDependencyException("MODEL_PROVIDER_HAS_MODELS",
                        "model provider has dependent models");
            }
            tx.modelProviders().delete(id, current.version());
            appendEvent(tx, "model_provider", id, "ModelProviderDeleted", idPayload(id), current.updatedAt(),
                    current.version());
            return null;
        });
    }

    public ModelRecord updateModelEnabled(UUID id, boolean enabled, Instant at) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(at, "at");
        return inTransaction(tx -> {
            ModelRecord current = tx.models().findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("model", id));
            if (!enabled && current.enabled()
                    && tx.models().countActiveAgentSpecReferences(current.providerId(), current.modelId()) > 0) {
                throw new ModelCatalogDependencyException("MODEL_IN_USE",
                        "model is referenced by an active agent spec");
            }
            if (current.enabled() == enabled) {
                return current;
            }
            ModelRecord updated = tx.models().updateEnabled(id, enabled, current.version(), at);
            appendEvent(tx, "model", id, "ModelEnabledChanged", idPayload(id), at, updated.version());
            return updated;
        });
    }

    public void deleteModel(UUID id) {
        Objects.requireNonNull(id, "id");
        inTransaction(tx -> {
            ModelRecord current = tx.models().findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("model", id));
            if (tx.models().countActiveAgentSpecReferences(current.providerId(), current.modelId()) > 0) {
                throw new ModelCatalogDependencyException("MODEL_IN_USE",
                        "model is referenced by an active agent spec");
            }
            tx.models().delete(id, current.version());
            appendEvent(tx, "model", id, "ModelDeleted", idPayload(id), current.updatedAt(), current.version());
            return null;
        });
    }

    public Optional<TaskRecord> findTask(UUID id) {
        return inTransaction(tx -> tx.tasks().findById(id));
    }

    public Optional<ArtifactRecord> findArtifact(UUID id) {
        return inTransaction(tx -> tx.artifacts().findById(id));
    }

    public Optional<TaskAttemptRecord> findTaskAttempt(UUID id) {
        return inTransaction(tx -> tx.taskAttempts().findById(id));
    }

    public List<ArtifactRecord> findArtifactsByTaskId(UUID taskId) {
        return inTransaction(tx -> tx.artifacts().findByTaskId(taskId));
    }

    public List<ArtifactRecord> findArtifactsByTaskIdAndAttemptId(UUID taskId, UUID attemptId) {
        return inTransaction(tx -> tx.artifacts().findByTaskIdAndAttemptId(taskId, attemptId));
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

    private static void assertPriceIdempotency(ModelPriceRepository.PriceIdempotency existing,
            String requestHash, String key) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(key, CREATE_MODEL_PRICE);
        }
    }
}
