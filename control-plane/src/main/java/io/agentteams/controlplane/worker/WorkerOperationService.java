package io.agentteams.controlplane.worker;

import io.agentteams.controlplane.api.CursorPage;
import io.agentteams.controlplane.api.CursorPageRequest;
import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.IdempotencyConflictException;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import io.agentteams.controlplane.service.WorkerLifecycleConflictException;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import io.agentteams.domain.agent.AgentPhase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public final class WorkerOperationService {

    private final FoundationPersistenceService persistence;
    private final Clock clock;
    private final Duration operationLease;
    private final ResourceScopeRepository resourceScopes;

    @Autowired
    public WorkerOperationService(FoundationPersistenceService persistence,
            ObjectProvider<ResourceScopeRepository> scopes) {
        this(persistence, Clock.systemUTC(), Duration.ofMinutes(2), scopes.getIfAvailable());
    }

    WorkerOperationService(FoundationPersistenceService persistence, Clock clock, Duration operationLease) {
        this(persistence, clock, operationLease, null);
    }

    WorkerOperationService(FoundationPersistenceService persistence, Clock clock, Duration operationLease,
            ResourceScopeRepository resourceScopes) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.operationLease = Objects.requireNonNull(operationLease, "operationLease");
        this.resourceScopes = resourceScopes;
        if (operationLease.isZero() || operationLease.isNegative()) {
            throw new IllegalArgumentException("operationLease must be positive");
        }
    }

    public WorkerOperation drain(UUID agentId, long expectedAgentVersion, String idempotencyKey) {
        return request(agentId, WorkerOperationType.DRAIN, expectedAgentVersion, idempotencyKey,
                null, null, null, null, "{}", PrincipalContext.actorOr("control-plane"),
                correlationOrRandom());
    }

    public WorkerOperation terminate(UUID agentId, long expectedAgentVersion, String idempotencyKey) {
        return request(agentId, WorkerOperationType.TERMINATE, expectedAgentVersion, idempotencyKey,
                null, null, null, null, "{}", PrincipalContext.actorOr("control-plane"),
                correlationOrRandom());
    }

    public WorkerOperation rollout(UUID agentId, WorkerRolloutRequest request) {
        Objects.requireNonNull(request, "request");
        return request(agentId, WorkerOperationType.ROLLOUT, request.expectedAgentVersion(),
                request.idempotencyKey(), request.imageDigest(), request.runtime(), request.configRevision(),
                request.secretGeneration(), request.previousStableSpec(), PrincipalContext.actorOr(request.owner()),
                correlationOr(request.correlationId()));
    }

    public WorkerOperation rollback(UUID operationId, long expectedVersion) {
        throw new IllegalArgumentException("Idempotency-Key is required");
    }

    /** Compatibility overload that fails closed until a request key is supplied. */
    public WorkerOperation rollback(UUID agentId, UUID operationId, long expectedVersion) {
        throw new IllegalArgumentException("Idempotency-Key is required");
    }

    /** Rolls back an operation while enforcing a durable request idempotency key. */
    public WorkerOperation rollback(UUID agentId, UUID operationId, long expectedVersion, String idempotencyKey) {
        Objects.requireNonNull(operationId, "operationId");
        requireKey(idempotencyKey);
        Instant now = clock.instant();
        return persistence.inTransaction(tx -> {
            WorkerOperation current = tx.workerOperations().findByIdForUpdate(operationId)
                    .orElseThrow(() -> new ResourceNotFoundException("worker operation", operationId));
            if (agentId != null && !agentId.equals(current.agentId())) {
                throw new ResourceNotFoundException("worker operation", operationId);
            }
            requireVisible(current.agentId());
            WorkerOperationRepository.RollbackRequest prior = tx.workerOperations().findRollback(operationId)
                    .orElse(null);
            if (prior != null) {
                assertRollbackRequest(prior, expectedVersion, idempotencyKey);
                return current;
            }
            if (current.version() != expectedVersion) {
                throw new io.agentteams.controlplane.persistence.OptimisticLockFailure(
                        "worker_operation", operationId, expectedVersion, current.version());
            }
            if (current.type() != WorkerOperationType.ROLLOUT
                    || current.status() != WorkerOperationStatus.FAILED) {
                throw new WorkerLifecycleConflictException("WORKER_ROLLBACK_NOT_ALLOWED");
            }
            WorkerOperationRepository.RollbackRequest request = new WorkerOperationRepository.RollbackRequest(
                    operationId, idempotencyKey, expectedVersion, now);
            if (!tx.workerOperations().insertRollback(request)) {
                WorkerOperationRepository.RollbackRequest winner = tx.workerOperations().findRollback(operationId)
                        .orElseThrow(() -> new IllegalStateException("rollback idempotency record disappeared"));
                assertRollbackRequest(winner, expectedVersion, idempotencyKey);
                return current;
            }
            WorkerOperation updated = tx.workerOperations().updateStatus(operationId, WorkerOperationStatus.ROLLED_BACK,
                    null, expectedVersion, now);
            FoundationPersistenceService.appendEvent(tx, "worker_operation", operationId, "WorkerOperationRolledBack",
                    "{\"operationId\":\"" + operationId + "\"}", now, updated.version());
            return updated;
        });
    }

    /** Reads an operation through its owning agent resource boundary. */
    public WorkerOperation get(UUID agentId, UUID operationId) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(operationId, "operationId");
        return persistence.inTransaction(tx -> {
            WorkerOperation operation = tx.workerOperations().findById(operationId)
                    .filter(candidate -> agentId.equals(candidate.agentId()))
                    .orElseThrow(() -> new ResourceNotFoundException("worker operation", operationId));
            requireVisible(agentId);
            return operation;
        });
    }

    public CursorPage<WorkerOperation> list(UUID agentId, CursorPageRequest request) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(request, "request");
        PrincipalContext.current().ifPresent(ignored -> requireVisible(agentId));
        io.agentteams.controlplane.security.Principal principal = PrincipalContext.current()
                .orElseThrow(() -> new io.agentteams.controlplane.security.AuthorizationException(
                        "authentication required"));
        java.util.List<WorkerOperation> rows = persistence.inTransaction(tx -> tx.workerOperations().findPage(
                agentId, principal, request.position(), request.pageSize() + 1, request.direction()));
        return CursorPage.fromRows(rows, request.pageSize(),
                operation -> new CursorPageRequest.Position(operation.createdAt(), operation.id()), clock.instant());
    }

    public java.util.Optional<WorkerOperationObservation> observation(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");
        return persistence.inTransaction(tx -> tx.workerOperations().findObservation(operationId));
    }

    /** Returns the non-expired rollout visible to trusted internal observers. */
    public java.util.Optional<WorkerOperation> active(UUID agentId, Instant now) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(now, "now");
        return persistence.inTransaction(tx -> tx.workerOperations().findActiveByAgent(agentId, now));
    }

    /** Returns the oldest failed rollout that has not yet been confirmed rolled back. */
    public java.util.Optional<WorkerOperation> failed(UUID agentId, Instant now) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(now, "now");
        return persistence.inTransaction(tx -> tx.workerOperations().findFailedRolloutByAgent(agentId, now));
    }

    /**
     * Advances a rollout only from independently observed Operator and Gateway
     * facts. A partial or stale observation keeps the durable operation RUNNING.
     */
    public WorkerOperation confirmRollout(UUID operationId, long expectedVersion,
            WorkerRolloutConfirmation confirmation) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(confirmation, "confirmation");
        return confirmObservation(operationId, expectedVersion, confirmation.observedAt(),
                (repository, id, observedAt) -> {
                    repository.recordOperatorObservation(id, confirmation.operatorReady(),
                            confirmation.operatorSpecDigest(), confirmation.operatorRuntime(),
                            confirmation.operatorConfigRevision(), confirmation.operatorSecretGeneration(), observedAt);
                    repository.recordGatewayObservation(id, confirmation.gatewayOnline(),
                            confirmation.gatewaySpecDigest(), confirmation.gatewayRuntime(),
                            confirmation.gatewayConfigRevision(), confirmation.gatewaySecretGeneration(), observedAt);
                });
    }

    public WorkerOperation confirmOperator(UUID operationId, long expectedVersion,
            WorkerOperatorObservation observation) {
        Objects.requireNonNull(observation, "observation");
        return confirmObservation(operationId, expectedVersion, observation.observedAt(),
                (repository, id, observedAt) -> repository.recordOperatorObservation(id, observation.ready(),
                        observation.specDigest(), observation.runtime(), observation.configRevision(),
                        observation.secretGeneration(), observedAt));
    }

    public WorkerOperation confirmGateway(UUID operationId, long expectedVersion,
            WorkerGatewayObservation observation) {
        Objects.requireNonNull(observation, "observation");
        return confirmObservation(operationId, expectedVersion, observation.observedAt(),
                (repository, id, observedAt) -> repository.recordGatewayObservation(id, observation.online(),
                        observation.specDigest(), observation.runtime(), observation.configRevision(),
                        observation.secretGeneration(), observedAt));
    }

    private WorkerOperation confirmObservation(UUID operationId, long expectedVersion, Instant observedAt,
            ObservationWriter writer) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(observedAt, "observedAt");
        Instant now = clock.instant();
        return persistence.inTransaction(tx -> {
            WorkerOperation current = tx.workerOperations().findByIdForUpdate(operationId)
                    .orElseThrow(() -> new IllegalArgumentException("worker operation does not exist: " + operationId));
            requireVisible(current.agentId());
            if (current.version() != expectedVersion) {
                throw new io.agentteams.controlplane.persistence.OptimisticLockFailure(
                        "worker_operation", operationId, expectedVersion, current.version());
            }
            if (current.type() != WorkerOperationType.ROLLOUT
                    || (current.status() != WorkerOperationStatus.PENDING
                    && current.status() != WorkerOperationStatus.RUNNING)) {
                throw new WorkerLifecycleConflictException("WORKER_ROLLOUT_CONFIRMATION_NOT_ALLOWED");
            }
            if (current.leaseExpiresAt() != null && !now.isBefore(current.leaseExpiresAt())) {
                WorkerOperation failed = tx.workerOperations().updateStatus(operationId,
                        WorkerOperationStatus.FAILED, "OPERATION_LEASE_EXPIRED", expectedVersion, now);
                FoundationPersistenceService.appendEvent(tx, "worker_operation", operationId,
                        "WorkerOperationLeaseExpired", "{\"operationId\":\"" + operationId + "\"}", now,
                        failed.version());
                return failed;
            }
            writer.write(tx.workerOperations(), operationId, observedAt);
            WorkerOperationObservation observation = tx.workerOperations().findObservation(operationId)
                    .orElseThrow(() -> new IllegalStateException("worker operation observation was not recorded"));
            WorkerOperationStatus next = observation.matches(current)
                    ? WorkerOperationStatus.SUCCEEDED : WorkerOperationStatus.RUNNING;
            if (current.status() == next) {
                return current;
            }
            WorkerOperation updated = tx.workerOperations().updateStatus(operationId, next, null,
                    expectedVersion, now);
            FoundationPersistenceService.appendEvent(tx, "worker_operation", operationId,
                    next == WorkerOperationStatus.SUCCEEDED ? "WorkerOperationSucceeded" : "WorkerOperationStarted",
                    "{\"operationId\":\"" + operationId + "\",\"type\":\"ROLLOUT\"}", now,
                    updated.version());
            return updated;
        });
    }

    @FunctionalInterface
    private interface ObservationWriter {
        void write(WorkerOperationRepository repository, UUID operationId, Instant observedAt);
    }

    private WorkerOperation request(UUID agentId, WorkerOperationType type, long expectedAgentVersion,
            String idempotencyKey, String requestedSpecDigest, String requestedRuntime,
            String requestedConfigRevision, String requestedSecretGeneration, String previousStableSpec,
            String owner, String correlationId) {
        Objects.requireNonNull(agentId, "agentId");
        requireKey(idempotencyKey);
        requireVisible(agentId);
        Instant now = clock.instant();
        return persistence.inTransaction(tx -> {
            var existing = tx.workerOperations().findByAgentAndIdempotencyKey(agentId, idempotencyKey);
            if (existing.isPresent()) {
                assertSameRequest(existing.get(), type, expectedAgentVersion, requestedSpecDigest, requestedRuntime,
                        requestedConfigRevision, requestedSecretGeneration, previousStableSpec, idempotencyKey);
                return existing.get();
            }
            AgentRecord current = tx.agents().findByIdForUpdate(agentId)
                    .orElseThrow(() -> new IllegalArgumentException("agent does not exist: " + agentId));
            // The first lookup is an optimistic fast path. A concurrent request
            // can have committed while this call waited for the agent row lock,
            // so repeat the lookup while holding that lock before validating the
            // caller's expected agent version.
            existing = tx.workerOperations().findByAgentAndIdempotencyKey(agentId, idempotencyKey);
            if (existing.isPresent()) {
                assertSameRequest(existing.get(), type, expectedAgentVersion, requestedSpecDigest, requestedRuntime,
                        requestedConfigRevision, requestedSecretGeneration, previousStableSpec, idempotencyKey);
                return existing.get();
            }
            if (current.version() != expectedAgentVersion) {
                throw new io.agentteams.controlplane.persistence.OptimisticLockFailure(
                        "agent", agentId, expectedAgentVersion, current.version());
            }
            var active = tx.workerOperations().findActiveByAgentForUpdate(agentId);
            if (active.isPresent()) {
                WorkerOperation inProgress = active.get();
                if (inProgress.leaseExpiresAt() != null && !now.isBefore(inProgress.leaseExpiresAt())) {
                    WorkerOperation expired = tx.workerOperations().updateStatus(inProgress.id(),
                            WorkerOperationStatus.FAILED, "OPERATION_LEASE_EXPIRED", inProgress.version(), now);
                    FoundationPersistenceService.appendEvent(tx, "worker_operation", expired.id(),
                            "WorkerOperationLeaseExpired", "{\"operationId\":\"" + expired.id() + "\"}",
                            now, expired.version());
                } else if (type == WorkerOperationType.TERMINATE
                        && inProgress.type() == WorkerOperationType.DRAIN
                        && tx.agentLeases().countActiveForAgent(agentId) == 0) {
                    WorkerOperation drained = tx.workerOperations().updateStatus(inProgress.id(),
                            WorkerOperationStatus.DRAINED, null, inProgress.version(), now);
                    FoundationPersistenceService.appendEvent(tx, "worker_operation", drained.id(),
                            "WorkerOperationDrained", "{\"operationId\":\"" + drained.id() + "\"}", now,
                            drained.version());
                } else {
                    throw new WorkerLifecycleConflictException("WORKER_OPERATION_IN_PROGRESS");
                }
            }
            if (type == WorkerOperationType.TERMINATE) {
                if (current.phase() != AgentPhase.DRAINING) {
                    throw new WorkerLifecycleConflictException("WORKER_MUST_BE_DRAINING");
                }
                if (tx.agentLeases().countActiveForAgent(agentId) > 0) {
                    throw new WorkerLifecycleConflictException("WORKER_HAS_ACTIVE_TASKS");
                }
            }
            if (type == WorkerOperationType.DRAIN
                    && current.phase() != AgentPhase.READY
                    && current.phase() != AgentPhase.BUSY
                    && current.phase() != AgentPhase.PROVISIONING) {
                throw new WorkerLifecycleConflictException("WORKER_NOT_DRAINABLE");
            }
            if (type == WorkerOperationType.ROLLOUT
                    && tx.agentLeases().countActiveForAgent(agentId) > 0) {
                throw new WorkerLifecycleConflictException("WORKER_HAS_ACTIVE_TASKS");
            }
            if (type == WorkerOperationType.ROLLOUT
                    && current.phase() != AgentPhase.READY
                    && current.phase() != AgentPhase.BUSY
                    && current.phase() != AgentPhase.PROVISIONING) {
                throw new WorkerLifecycleConflictException("WORKER_NOT_ROLLOUTABLE");
            }
            WorkerOperation operation = WorkerOperation.pending(UUID.randomUUID(), agentId, type,
                    requestedSpecDigest, requestedRuntime, requestedConfigRevision, requestedSecretGeneration,
                    previousStableSpec, idempotencyKey, expectedAgentVersion, owner, now.plus(operationLease),
                    correlationId, now);
            if (type == WorkerOperationType.DRAIN) {
                tx.agents().updatePhase(agentId, AgentPhase.DRAINING, expectedAgentVersion, now);
            }
            tx.workerOperations().insert(operation);
            FoundationPersistenceService.appendEvent(tx, "agent", agentId, "WorkerOperationRequested",
                    "{\"operationId\":\"" + operation.id() + "\",\"type\":\"" + type.name() + "\"}",
                    now, type == WorkerOperationType.DRAIN ? current.version() + 1 : current.version());
            return operation;
        });
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw new IllegalArgumentException("idempotencyKey must be between 1 and 255 characters");
        }
    }

    private static void assertSameRequest(WorkerOperation existing, WorkerOperationType type,
            long expectedAgentVersion, String requestedSpecDigest, String requestedRuntime,
            String requestedConfigRevision, String requestedSecretGeneration, String previousStableSpec,
            String idempotencyKey) {
        if (existing.type() != type
                || existing.expectedAgentVersion() != expectedAgentVersion
                || !Objects.equals(existing.requestedSpecDigest(), requestedSpecDigest)
                || !Objects.equals(existing.requestedRuntime(), requestedRuntime)
                || !Objects.equals(existing.requestedConfigRevision(), requestedConfigRevision)
                || !Objects.equals(existing.requestedSecretGeneration(), requestedSecretGeneration)
                || !Objects.equals(existing.previousStableSpec(), previousStableSpec)) {
            throw new IdempotencyConflictException(idempotencyKey, "worker operation");
        }
    }

    private static void assertRollbackRequest(WorkerOperationRepository.RollbackRequest existing,
            long expectedVersion, String idempotencyKey) {
        if (!existing.idempotencyKey().equals(idempotencyKey) || existing.expectedVersion() != expectedVersion) {
            throw new IdempotencyConflictException(idempotencyKey, "worker rollback");
        }
    }

    private void requireVisible(UUID agentId) {
        if (resourceScopes != null) {
            resourceScopes.requireVisible("WORKER", agentId);
        }
    }

    public static int recoverExpiredOperations(
            io.agentteams.controlplane.persistence.FoundationTransaction tx, Instant now) {
        int recovered = 0;
        for (WorkerOperation expired : tx.workerOperations().findExpiredForUpdate(now)) {
            WorkerOperation failed = tx.workerOperations().updateStatus(expired.id(), WorkerOperationStatus.FAILED,
                    "OPERATION_LEASE_EXPIRED", expired.version(), now);
            FoundationPersistenceService.appendEvent(tx, "worker_operation", failed.id(),
                    "WorkerOperationLeaseExpired", "{\"operationId\":\"" + failed.id() + "\"}", now,
                    failed.version());
            recovered++;
        }
        return recovered;
    }

    private static String correlationOr(String fallback) {
        String current = MDC.get("correlationId");
        return current == null || current.isBlank() ? fallback : current;
    }

    private static String correlationOrRandom() {
        return correlationOr(UUID.randomUUID().toString());
    }
}
