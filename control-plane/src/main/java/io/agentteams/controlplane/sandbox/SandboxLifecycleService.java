package io.agentteams.controlplane.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.SandboxObservation;
import io.agentteams.application.api.SandboxPolicy;
import io.agentteams.application.api.SandboxProvisionCommand;
import io.agentteams.application.api.SandboxProviderException;
import io.agentteams.application.api.SandboxProviderPhase;
import io.agentteams.application.api.SandboxProviderRef;
import io.agentteams.application.api.SandboxRenewCommand;
import io.agentteams.application.api.SandboxProfile;
import io.agentteams.application.api.SandboxRequest;
import io.agentteams.application.api.SandboxRuntimePort;
import io.agentteams.application.api.SandboxStatus;
import io.agentteams.application.api.SandboxTerminationReason;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.FoundationTransaction;
import io.agentteams.controlplane.persistence.TaskAttemptRecord;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.persistence.TaskSandboxRecord;
import io.agentteams.domain.task.TaskPhase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates database facts and an external sandbox provider.
 *
 * <p>Every provider call is intentionally made after the database transaction
 * that claims a row has committed. A provider outage therefore cannot hold a
 * task-assignment transaction open.</p>
 */
public final class SandboxLifecycleService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
    private static final Duration MIN_TTL = Duration.ofSeconds(60);
    private static final Duration MAX_TTL = Duration.ofHours(24);

    private final FoundationPersistenceService persistence;
    private final SandboxRuntimePort runtime;
    private final SandboxPolicyService policyService;
    private final SandboxRuntimeProperties properties;
    private final String operationOwner;

    public SandboxLifecycleService(FoundationPersistenceService persistence, SandboxRuntimePort runtime) {
        this(persistence, runtime, new SandboxRuntimeProperties(), "sandbox-lifecycle", new SandboxPolicyService());
    }

    public SandboxLifecycleService(FoundationPersistenceService persistence, SandboxRuntimePort runtime,
            SandboxRuntimeProperties properties, String operationOwner) {
        this(persistence, runtime, properties, operationOwner, new SandboxPolicyService());
    }

    public SandboxLifecycleService(FoundationPersistenceService persistence, SandboxRuntimePort runtime,
            SandboxRuntimeProperties properties, String operationOwner, SandboxPolicyService policyService) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.policyService = Objects.requireNonNull(policyService, "policyService");
        if (operationOwner == null || operationOwner.isBlank()) {
            throw new IllegalArgumentException("operationOwner must not be blank");
        }
        this.operationOwner = operationOwner;
    }

    /** Reads the small sandbox section without copying the complete task spec. */
    public static Optional<SandboxRequest> requestFor(TaskRecord task, TaskAttemptRecord attempt, Instant now) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(now, "now");
        try {
            JsonNode root = OBJECT_MAPPER.readTree(task.specJson());
            JsonNode sandbox = root == null ? null : root.get("sandbox");
            if (sandbox == null || !sandbox.isObject()) {
                return Optional.empty();
            }
            String profileText = sandbox.path("profile").asText("NONE");
            SandboxProfile profile = SandboxProfile.valueOf(profileText);
            if (profile == SandboxProfile.NONE) {
                return Optional.empty();
            }
            long ttlSeconds = sandbox.has("ttlSeconds")
                    ? sandbox.path("ttlSeconds").asLong(-1) : DEFAULT_TTL.toSeconds();
            Duration ttl = Duration.ofSeconds(ttlSeconds);
            if (ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
                throw new IllegalArgumentException("sandbox ttlSeconds must be between 60 and 86400");
            }
            String template = sandbox.path("template").asText("default");
            return Optional.of(SandboxRequest.of(task.id(), attempt.id(), profile, ttl, template, now));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("task sandbox spec is invalid", error);
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("task spec cannot be parsed for sandbox", error);
        }
    }

    /** Creates the durable request while still inside the assignment transaction. */
    public static Optional<TaskSandboxRecord> requestInTransaction(FoundationTransaction tx,
            TaskRecord task, TaskAttemptRecord attempt, Instant now) {
        Optional<SandboxRequest> request = requestFor(task, attempt, now);
        if (request.isEmpty()) {
            return Optional.empty();
        }
        SandboxRequest value = request.get();
        TaskSandboxRecord record = new TaskSandboxRecord(UUID.randomUUID(), value.taskId(), value.attemptId(),
                value.idempotencyKey(), null, value.profile(), SandboxStatus.REQUESTED, value.template(), null,
                value.requestedAt(), value.expiresAt(), null, null, null, null, null, now, now, 0);
        if (!tx.taskSandboxes().insertIfAbsent(record)) {
            return tx.taskSandboxes().findByAttemptId(value.attemptId());
        }
        FoundationPersistenceService.appendEvent(tx, "task_sandbox", record.id(),
                "SandboxProvisionRequested", "{\"taskId\":\"" + value.taskId()
                        + "\",\"attemptId\":\"" + value.attemptId() + "\",\"profile\":\""
                        + value.profile() + "\",\"expiresAt\":\"" + value.expiresAt() + "\"}", now, 0);
        return Optional.of(record);
    }

    public int recoverStaleOperations(Instant now) {
        Objects.requireNonNull(now, "now");
        return persistence.inTransaction(tx -> tx.taskSandboxes().recoverStaleOperations(now,
                properties.getBaseRetryDelay(), properties.getMaxRetryDelay()));
    }

    public int provisionRequested(Instant now, int limit) {
        List<TaskSandboxRecord> claimed = persistence.inTransaction(tx -> {
            List<TaskSandboxRecord> requested = tx.taskSandboxes().claimRequested(now, limit, operationOwner,
                    operationExpiresAt(now));
            return requested.stream()
                    .map(record -> tx.taskSandboxes().markProvisioning(record.id(), record.version(), now))
                    .toList();
        });
        int completed = 0;
        for (TaskSandboxRecord provisioning : claimed) {
            try {
                SandboxProvisionCommand command = SandboxProvisionCommand.from(toRequest(provisioning));
                var receipt = runtime.ensureProvisioned(command);
                SandboxObservation observation = runtime.inspect(receipt.providerRef());
                TaskSandboxRecord bound = persistence.inTransaction(tx -> tx.taskSandboxes().updateProviderBinding(
                        provisioning.id(), receipt.providerRef().provider(), receipt.providerRef().resourceId(),
                        receipt.providerRef().resourceUid(), SandboxStatus.PROVISIONING, null, command.expiresAt(),
                        receipt.observedGeneration(), null, "{}", provisioning.version(), now));
                applyObservation(bound, observation, now);
                completed++;
            } catch (RuntimeException error) {
                releaseFailure(provisioning, "PROVISION", properties.getMaxProvisionAttempts(), error, now);
            }
        }
        return completed;
    }

    public int observeActive(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        int observed = 0;
        List<TaskSandboxRecord> candidates = claimCandidates(now, limit, "OBSERVE",
                tx -> tx.taskSandboxes().findActiveForObservation(now, limit));
        for (TaskSandboxRecord candidate : candidates) {
            try {
                SandboxObservation observation = runtime.inspect(providerRef(candidate));
                applyObservation(candidate, observation, now);
                observed++;
            } catch (RuntimeException error) {
                releaseFailure(candidate, "OBSERVE", properties.getMaxProvisionAttempts(), error, now);
            }
        }
        return observed;
    }

    public int renewExpiring(Instant now, Duration renewBefore, Duration extension, int limit) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(renewBefore, "renewBefore");
        Objects.requireNonNull(extension, "extension");
        if (renewBefore.isNegative()) throw new IllegalArgumentException("renewBefore must not be negative");
        if (extension.isZero() || extension.isNegative()) {
            throw new IllegalArgumentException("extension must be positive");
        }
        List<TaskSandboxRecord> candidates = claimCandidates(now, limit, "RENEW",
                tx -> tx.taskSandboxes().findExpiring(now, renewBefore, limit));
        int renewed = 0;
        for (TaskSandboxRecord candidate : candidates) {
            if (candidate.providerResourceId() == null || candidate.providerResourceUid() == null) {
                continue;
            }
            try {
                Instant expiresAt = now.plus(extension);
                runtime.ensureExpiry(new SandboxRenewCommand(providerRef(candidate), expiresAt));
                persistence.inTransaction(tx -> tx.taskSandboxes().updateExpiry(candidate.id(), expiresAt,
                        candidate.version(), now));
                renewed++;
            } catch (RuntimeException error) {
                releaseFailure(candidate, "RENEW", properties.getMaxProvisionAttempts(), error, now);
            }
        }
        return renewed;
    }

    /** Compatibility name retained for callers on the previous scheduler API. */
    public int renewRunning(Instant now, Duration extension, int limit) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(extension, "extension");
        if (extension.isZero() || extension.isNegative()) throw new IllegalArgumentException("extension must be positive");
        int renewed = 0;
        for (TaskSandboxRecord candidate : claimCandidates(now, limit, "RENEW",
                tx -> tx.taskSandboxes().findRenewable(limit))) {
            if (candidate.providerResourceId() == null || candidate.providerResourceUid() == null) continue;
            try {
                Instant expiresAt = now.plus(extension);
                runtime.ensureExpiry(new SandboxRenewCommand(providerRef(candidate), expiresAt));
                persistence.inTransaction(tx -> tx.taskSandboxes().updateExpiry(candidate.id(), expiresAt,
                        candidate.version(), now));
                renewed++;
            } catch (RuntimeException error) {
                releaseFailure(candidate, "RENEW", properties.getMaxProvisionAttempts(), error, now);
            }
        }
        return renewed;
    }

    public int expire(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        int expired = 0;
        for (TaskSandboxRecord candidate : persistence.inTransaction(tx -> tx.taskSandboxes()
                .findExpired(now, limit))) {
            try {
                persistence.inTransaction(tx -> tx.taskSandboxes().markExpired(candidate.id(), candidate.version(), now));
                expired++;
            } catch (RuntimeException ignored) {
                // A concurrent lifecycle transition is picked up by the next leader.
            }
        }
        return expired;
    }

    public int terminateStopping(Instant now, int limit) {
        List<TaskSandboxRecord> candidates = claimCandidates(now, limit, "TERMINATE",
                tx -> tx.taskSandboxes().findStopping(limit));
        int terminated = 0;
        for (TaskSandboxRecord candidate : candidates) {
            TaskSandboxRecord operation = candidate;
            try {
                if (candidate.status() != SandboxStatus.STOPPING) {
                    operation = persistence.inTransaction(tx -> tx.taskSandboxes().markStopping(
                            candidate.id(), candidate.version(), now));
                }
                if (operation.providerResourceId() != null && operation.providerResourceUid() != null) {
                    runtime.ensureTerminated(new io.agentteams.application.api.SandboxTerminationCommand(
                            providerRef(operation), operation.terminationReason() == null
                                    ? SandboxTerminationReason.OPERATOR_CLEANUP : operation.terminationReason()));
                } else {
                    TaskSandboxRecord destroyed = operation;
                    persistence.inTransaction(tx -> tx.taskSandboxes().markDestroyed(
                            destroyed.id(), destroyed.version(), now));
                }
                terminated++;
            } catch (RuntimeException error) {
                releaseFailure(operation, "TERMINATE", properties.getMaxTerminateAttempts(), error, now);
            }
        }
        return terminated;
    }

    public int observeStopping(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        int destroyed = 0;
        for (TaskSandboxRecord candidate : claimCandidates(now, limit, "OBSERVE_STOPPING",
                tx -> tx.taskSandboxes().findStopping(limit))) {
            try {
                SandboxObservation observation = runtime.inspect(providerRef(candidate));
                if (observation.phase() == SandboxProviderPhase.DESTROYED
                        || observation.phase() == SandboxProviderPhase.LOST) {
                    persistence.inTransaction(tx -> tx.taskSandboxes().markDestroyed(candidate.id(), candidate.version(), now));
                    destroyed++;
                }
            } catch (RuntimeException error) {
                releaseFailure(candidate, "OBSERVE_STOPPING", properties.getMaxTerminateAttempts(), error, now);
            }
        }
        return destroyed;
    }

    private SandboxRequest toRequest(TaskSandboxRecord record) {
        Duration ttl = Duration.between(record.requestedAt(), record.expiresAt());
        SandboxRequest request = SandboxRequest.of(record.taskId(), record.attemptId(), record.profile(), ttl,
                record.template(), record.requestedAt());
        SandboxPolicy effective = policyService.resolve(request.policy());
        return new SandboxRequest(request.taskId(), request.attemptId(), request.profile(), request.ttl(),
                request.template(), request.requestedAt(), request.idempotencyKey(), effective);
    }

    private SandboxProviderRef providerRef(TaskSandboxRecord record) {
        String resourceId = record.providerResourceId() == null ? record.providerSandboxId()
                : record.providerResourceId();
        if (resourceId == null || record.providerResourceUid() == null) {
            throw new SandboxProviderException(io.agentteams.application.api.SandboxFailureCategory.PROVIDER_RESOURCE_LOST,
                    "sandbox provider reference is incomplete");
        }
        return new SandboxProviderRef(record.provider(), resourceId, record.providerResourceUid());
    }

    private void applyObservation(TaskSandboxRecord claimed, SandboxObservation observation, Instant now) {
        persistence.inTransaction(tx -> {
            TaskSandboxRecord current = tx.taskSandboxes().findById(claimed.id()).orElseThrow();
            if (observation.observedGeneration() < current.observedGeneration()) return null;
            SandboxStatus status = statusFor(current, observation);
            if (observation.phase() == SandboxProviderPhase.DESTROYED) {
                if (current.status() == SandboxStatus.STOPPING || current.status() == SandboxStatus.EXPIRED
                        || current.status() == SandboxStatus.LOST) {
                    tx.taskSandboxes().markDestroyed(current.id(), current.version(), now);
                }
                return null;
            }
            String failureCode = observation.failure() == null ? null : observation.failure().category().name();
            String failureMessage = observation.failure() == null ? null : observation.failure().message();
            TaskSandboxRecord updated = tx.taskSandboxes().updateObserved(current.id(), status,
                    observation.providerRef().resourceUid(), observation.endpointRef(), observation.expiresAt(),
                    observation.observedGeneration(), observation.workloadUid(), failureCode, failureMessage,
                    current.version(), now);
            if (status == SandboxStatus.READY && updated.dispatchEventId() == null) {
                TaskRecord task = tx.tasks().findById(updated.taskId()).orElseThrow();
                TaskAttemptRecord attempt = tx.taskAttempts().findById(updated.attemptId()).orElseThrow();
                var assignment = tx.findAssignmentByAttemptId(updated.attemptId()).orElseThrow();
                var agent = tx.agents().findById(assignment.agentId()).orElseThrow();
                var lease = tx.agentLeases().findById(attempt.leaseId()).orElseThrow();
                if (task.phase() == TaskPhase.ASSIGNED) {
                    UUID eventId = FoundationPersistenceService.appendEvent(tx, "task", task.id(), "TaskAssigned",
                            io.agentteams.controlplane.service.TaskAssignmentService.taskAssignedPayload(
                                    task, agent, attempt, assignment, lease, updated), now, task.version());
                    tx.taskSandboxes().markDispatched(updated.id(), eventId, now, updated.version());
                }
            }
            return null;
        });
    }

    private void releaseFailure(TaskSandboxRecord candidate, String operationKind, int maxAttempts,
            RuntimeException error, Instant now) {
        try {
            persistence.inTransaction(tx -> tx.taskSandboxes().releaseOperation(candidate.id(), candidate.version(),
                    operationOwner, operationKind, maxAttempts, properties.getBaseRetryDelay(),
                    properties.getMaxRetryDelay(), failureCode(error), error.getMessage(), now,
                    candidate.retryCount()));
        } catch (RuntimeException ignored) {
            // A stale version or a provider failure is retried by the next leader.
        }
    }

    private List<TaskSandboxRecord> claimCandidates(Instant now, int limit, String operationKind,
            java.util.function.Function<io.agentteams.controlplane.persistence.FoundationTransaction,
                    List<TaskSandboxRecord>> finder) {
        return persistence.inTransaction(tx -> finder.apply(tx).stream()
                .map(record -> tx.taskSandboxes().claimOperation(record.id(), operationOwner, operationKind,
                        now, operationExpiresAt(now)))
                .flatMap(Optional::stream)
                .toList());
    }

    private Instant operationExpiresAt(Instant now) {
        return now.plus(properties.getOperationTimeout());
    }

    private static SandboxStatus statusFor(TaskSandboxRecord current, SandboxObservation observation) {
        if (observation.phase() == SandboxProviderPhase.READY
                && observation.endpointRef() != null && !observation.endpointRef().isBlank()
                && observation.workloadUid() != null && !observation.workloadUid().isBlank()
                && observation.failure() == null) {
            return current.status() == SandboxStatus.RUNNING ? SandboxStatus.RUNNING : SandboxStatus.READY;
        }
        return switch (observation.phase()) {
            case PROVISIONING, REQUESTED -> SandboxStatus.PROVISIONING;
            case RUNNING -> SandboxStatus.RUNNING;
            case STOPPING -> SandboxStatus.STOPPING;
            case FAILED -> SandboxStatus.FAILED;
            case EXPIRED -> SandboxStatus.EXPIRED;
            case LOST -> SandboxStatus.LOST;
            case DESTROYED -> SandboxStatus.DESTROYED;
            case READY -> SandboxStatus.PROVISIONING;
        };
    }

    private static String failureCode(RuntimeException error) {
        return error instanceof SandboxProviderException provider ? provider.category().name() : "PROVIDER_FAILURE";
    }

}
