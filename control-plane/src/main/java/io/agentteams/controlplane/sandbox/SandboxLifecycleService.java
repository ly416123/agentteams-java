package io.agentteams.controlplane.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.SandboxHandle;
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

    public SandboxLifecycleService(FoundationPersistenceService persistence, SandboxRuntimePort runtime) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
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

    public int provisionRequested(Instant now, int limit) {
        List<TaskSandboxRecord> claimed = persistence.inTransaction(tx -> {
            List<TaskSandboxRecord> requested = tx.taskSandboxes().claimRequested(now, limit);
            return requested.stream()
                    .map(record -> tx.taskSandboxes().markProvisioning(record.id(), record.version(), now))
                    .toList();
        });
        int completed = 0;
        for (TaskSandboxRecord provisioning : claimed) {
            try {
                SandboxHandle handle = runtime.provision(toRequest(provisioning))
                        .withOwner(provisioning.taskId(), provisioning.attemptId());
                TaskSandboxRecord ready = persistence.inTransaction(tx -> tx.taskSandboxes().markReady(
                        provisioning.id(), handle.providerSandboxId(), handle.endpointRef(), handle.expiresAt(),
                        provisioning.version() + 1, now));
                publishTaskAssigned(ready, now);
                completed++;
            } catch (RuntimeException error) {
                persistence.inTransaction(tx -> tx.taskSandboxes().markFailed(provisioning.id(),
                        "PROVISION_FAILED", error.getMessage(), provisioning.version() + 1, now));
            }
        }
        return completed;
    }

    public int renewRunning(Instant now, Duration extension, int limit) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(extension, "extension");
        if (extension.isZero() || extension.isNegative()) {
            throw new IllegalArgumentException("extension must be positive");
        }
        List<TaskSandboxRecord> candidates = persistence.inTransaction(tx -> tx.taskSandboxes()
                .findRenewable(limit));
        int renewed = 0;
        for (TaskSandboxRecord candidate : candidates) {
            if (candidate.providerSandboxId() == null) {
                continue;
            }
            runtime.renew(candidate.providerSandboxId(), now.plus(extension));
            persistence.inTransaction(tx -> tx.taskSandboxes().updateExpiry(candidate.id(), now.plus(extension),
                    candidate.version(), now));
            renewed++;
        }
        return renewed;
    }

    public int terminateStopping(Instant now, int limit) {
        List<TaskSandboxRecord> candidates = persistence.inTransaction(tx -> tx.taskSandboxes()
                .findStopping(limit));
        int terminated = 0;
        for (TaskSandboxRecord candidate : candidates) {
            if (candidate.providerSandboxId() != null) {
                runtime.terminate(candidate.providerSandboxId(), candidate.terminationReason() == null
                        ? SandboxTerminationReason.OPERATOR_CLEANUP : candidate.terminationReason());
            }
            persistence.inTransaction(tx -> tx.taskSandboxes().markDestroyed(candidate.id(), candidate.version(), now));
            terminated++;
        }
        return terminated;
    }

    private SandboxRequest toRequest(TaskSandboxRecord record) {
        Duration ttl = Duration.between(record.requestedAt(), record.expiresAt());
        return SandboxRequest.of(record.taskId(), record.attemptId(), record.profile(), ttl, record.template(),
                record.requestedAt());
    }

    private void publishTaskAssigned(TaskSandboxRecord sandbox, Instant now) {
        persistence.inTransaction(tx -> {
            TaskRecord task = tx.tasks().findById(sandbox.taskId()).orElseThrow();
            TaskAttemptRecord attempt = tx.taskAttempts().findById(sandbox.attemptId()).orElseThrow();
            var assignment = tx.findAssignmentByAttemptId(sandbox.attemptId()).orElseThrow();
            var agent = tx.agents().findById(assignment.agentId()).orElseThrow();
            var lease = tx.agentLeases().findById(attempt.leaseId()).orElseThrow();
            if (task.phase() != TaskPhase.ASSIGNED || sandbox.status() != SandboxStatus.READY) {
                throw new IllegalStateException("sandbox is not ready for TaskAssigned publication");
            }
            FoundationPersistenceService.appendEvent(tx, "task", task.id(), "TaskAssigned",
                    io.agentteams.controlplane.service.TaskAssignmentService.taskAssignedPayload(
                            task, agent, attempt, assignment, lease, sandbox), now, task.version());
            return null;
        });
    }
}
