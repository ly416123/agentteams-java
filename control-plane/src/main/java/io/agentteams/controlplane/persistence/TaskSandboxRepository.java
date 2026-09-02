package io.agentteams.controlplane.persistence;

import io.agentteams.application.api.SandboxProfile;
import io.agentteams.application.api.SandboxStatus;
import io.agentteams.application.api.SandboxTerminationReason;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class TaskSandboxRepository {

    private static final String COLUMN_LIST = """
            id, task_id, attempt_id, idempotency_key, provider_sandbox_id, profile, status,
                   template, endpoint_ref, requested_at, expires_at, last_observed_at, terminated_at,
                   termination_reason, failure_code, redacted_failure_message, created_at, updated_at, version,
                   provider, provider_resource_id, provider_resource_uid, observed_generation, workload_uid,
                   desired_state, operation_owner, operation_expires_at, operation_kind, retry_count,
                   next_attempt_at, last_dispatched_at, dispatch_event_id, details::text""";
    private static final String SELECT_COLUMNS = "SELECT " + COLUMN_LIST + " FROM task_sandboxes";

    private final JdbcTemplate jdbc;

    TaskSandboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TaskSandboxRecord sandbox) {
        jdbc.update("""
                INSERT INTO task_sandboxes
                    (id, task_id, attempt_id, idempotency_key, provider_sandbox_id, profile, status,
                     template, endpoint_ref, requested_at, expires_at, last_observed_at, terminated_at,
                    termination_reason, failure_code, redacted_failure_message, created_at, updated_at, version,
                    provider, provider_resource_id, provider_resource_uid, observed_generation, workload_uid,
                    desired_state, operation_owner, operation_expires_at, operation_kind, retry_count,
                    next_attempt_at, last_dispatched_at, dispatch_event_id, details)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, sandbox.id(), sandbox.taskId(), sandbox.attemptId(), sandbox.idempotencyKey(),
                sandbox.providerSandboxId(), sandbox.profile().name(), sandbox.status().name(), sandbox.template(),
                sandbox.endpointRef(), JdbcSupport.timestamp(sandbox.requestedAt()),
                JdbcSupport.timestamp(sandbox.expiresAt()), nullableTimestamp(sandbox.lastObservedAt()),
                nullableTimestamp(sandbox.terminatedAt()), nullableReason(sandbox.terminationReason()),
                sandbox.failureCode(), JdbcSupport.failureMessage(sandbox.redactedFailureMessage()),
                JdbcSupport.timestamp(sandbox.createdAt()), JdbcSupport.timestamp(sandbox.updatedAt()), sandbox.version(),
                sandbox.provider(), sandbox.providerResourceId(), sandbox.providerResourceUid(),
                sandbox.observedGeneration(), sandbox.workloadUid(), sandbox.desiredState(), sandbox.operationOwner(),
                nullableTimestamp(sandbox.operationExpiresAt()), sandbox.operationKind(), sandbox.retryCount(),
                JdbcSupport.timestamp(sandbox.nextAttemptAt()), nullableTimestamp(sandbox.lastDispatchedAt()),
                sandbox.dispatchEventId(), JdbcSupport.json(sandbox.detailsJson()));
    }

    public boolean insertIfAbsent(TaskSandboxRecord sandbox) {
        int inserted = jdbc.update("""
                INSERT INTO task_sandboxes
                    (id, task_id, attempt_id, idempotency_key, provider_sandbox_id, profile, status,
                     template, endpoint_ref, requested_at, expires_at, last_observed_at, terminated_at,
                    termination_reason, failure_code, redacted_failure_message, created_at, updated_at, version,
                    provider, provider_resource_id, provider_resource_uid, observed_generation, workload_uid,
                    desired_state, operation_owner, operation_expires_at, operation_kind, retry_count,
                    next_attempt_at, last_dispatched_at, dispatch_event_id, details)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (attempt_id) DO NOTHING
                """, sandbox.id(), sandbox.taskId(), sandbox.attemptId(), sandbox.idempotencyKey(),
                sandbox.providerSandboxId(), sandbox.profile().name(), sandbox.status().name(), sandbox.template(),
                sandbox.endpointRef(), JdbcSupport.timestamp(sandbox.requestedAt()),
                JdbcSupport.timestamp(sandbox.expiresAt()), nullableTimestamp(sandbox.lastObservedAt()),
                nullableTimestamp(sandbox.terminatedAt()), nullableReason(sandbox.terminationReason()),
                sandbox.failureCode(), JdbcSupport.failureMessage(sandbox.redactedFailureMessage()),
                JdbcSupport.timestamp(sandbox.createdAt()), JdbcSupport.timestamp(sandbox.updatedAt()), sandbox.version(),
                sandbox.provider(), sandbox.providerResourceId(), sandbox.providerResourceUid(),
                sandbox.observedGeneration(), sandbox.workloadUid(), sandbox.desiredState(), sandbox.operationOwner(),
                nullableTimestamp(sandbox.operationExpiresAt()), sandbox.operationKind(), sandbox.retryCount(),
                JdbcSupport.timestamp(sandbox.nextAttemptAt()), nullableTimestamp(sandbox.lastDispatchedAt()),
                sandbox.dispatchEventId(), JdbcSupport.json(sandbox.detailsJson()));
        return inserted == 1;
    }

    public java.util.List<TaskSandboxRecord> claimRequested(Instant now, int limit) {
        return claimRequested(now, limit, "sandbox-lifecycle", now.plus(Duration.ofMinutes(2)));
    }

    public java.util.List<TaskSandboxRecord> claimRequested(Instant now, int limit, String owner,
            Instant operationExpiresAt) {
        if (limit <= 0 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        requireOperationLease(owner, operationExpiresAt);
        java.util.List<TaskSandboxRecord> requested = jdbc.query(SELECT_COLUMNS + """
                 WHERE status = 'REQUESTED' AND expires_at > ?
                   AND next_attempt_at <= ?
                   AND (operation_owner IS NULL OR operation_expires_at <= ?)
                 ORDER BY requested_at, id
                 FOR UPDATE SKIP LOCKED
                LIMIT ?
                """, this::map, JdbcSupport.timestamp(now), JdbcSupport.timestamp(now),
                JdbcSupport.timestamp(now), limit);
        java.util.List<TaskSandboxRecord> claimed = new ArrayList<>();
        for (TaskSandboxRecord record : requested) {
            claimOperation(record.id(), owner, "PROVISION", now, operationExpiresAt)
                    .ifPresent(claimed::add);
        }
        return claimed;
    }

    public Optional<TaskSandboxRecord> claimOperation(UUID id, String owner, String operationKind,
            Instant now, Instant operationExpiresAt) {
        requireOperationLease(owner, operationExpiresAt);
        int updated = jdbc.update("""
                UPDATE task_sandboxes
                   SET operation_owner = ?, operation_expires_at = ?, operation_kind = ?,
                       updated_at = ?, version = version + 1
                 WHERE id = ? AND next_attempt_at <= ?
                   AND (operation_owner IS NULL OR operation_expires_at <= ?)
                """, owner, JdbcSupport.timestamp(operationExpiresAt), operationKind,
                JdbcSupport.timestamp(now), id, JdbcSupport.timestamp(now), JdbcSupport.timestamp(now));
        if (updated == 0) return Optional.empty();
        return findById(id);
    }

    public java.util.List<TaskSandboxRecord> findRenewable(int limit) {
        if (limit <= 0 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        return findByStatuses(java.util.List.of(SandboxStatus.READY, SandboxStatus.RUNNING), limit);
    }

    public java.util.List<TaskSandboxRecord> findActiveForObservation(Instant now, int limit) {
        if (limit <= 0 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE status IN ('PROVISIONING', 'READY', 'RUNNING') AND desired_state = 'ACTIVE'
                   AND next_attempt_at <= ?
                 ORDER BY updated_at, id LIMIT ?
                """, this::map, JdbcSupport.timestamp(now), limit);
    }

    /** Metadata listing used by the management console; scope filtering is applied by the controller. */
    public java.util.List<TaskSandboxRecord> findLatest(int limit) {
        if (limit <= 0 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        return jdbc.query(SELECT_COLUMNS + " ORDER BY updated_at DESC, id DESC LIMIT ?", this::map, limit);
    }

    public java.util.List<TaskSandboxRecord> findExpiring(Instant now, java.time.Duration renewBefore, int limit) {
        if (renewBefore == null || renewBefore.isNegative()) throw new IllegalArgumentException("renewBefore must not be negative");
        if (limit <= 0 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE status IN ('READY', 'RUNNING') AND desired_state = 'ACTIVE'
                   AND expires_at <= ? AND expires_at > ? AND next_attempt_at <= ?
                 ORDER BY expires_at, id LIMIT ?
                """, this::map, JdbcSupport.timestamp(now.plus(renewBefore)), JdbcSupport.timestamp(now),
                JdbcSupport.timestamp(now), limit);
    }

    public java.util.List<TaskSandboxRecord> findExpired(Instant now, int limit) {
        if (limit <= 0 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE status IN ('PROVISIONING', 'READY', 'RUNNING') AND desired_state = 'ACTIVE'
                   AND expires_at <= ? AND next_attempt_at <= ?
                 ORDER BY expires_at, id LIMIT ?
                """, this::map, JdbcSupport.timestamp(now), JdbcSupport.timestamp(now), limit);
    }

    public int recoverStaleOperations(Instant now) {
        return recoverStaleOperations(now, Duration.ofSeconds(1), Duration.ofMinutes(1));
    }

    public int recoverStaleOperations(Instant now, Duration baseRetryDelay, Duration maxRetryDelay) {
        validateRetryDelays(baseRetryDelay, maxRetryDelay);
        List<Map<String, Object>> stale = jdbc.queryForList("""
                SELECT id, retry_count FROM task_sandboxes
                 WHERE operation_owner IS NOT NULL AND operation_expires_at <= ?
                 FOR UPDATE SKIP LOCKED
                """, JdbcSupport.timestamp(now));
        int recovered = 0;
        for (Map<String, Object> row : stale) {
            UUID id = (UUID) row.get("id");
            int retryCount = ((Number) row.get("retry_count")).intValue();
            int updated = jdbc.update("""
                    UPDATE task_sandboxes SET operation_owner = NULL, operation_expires_at = NULL,
                           operation_kind = NULL, retry_count = retry_count + 1,
                           next_attempt_at = ?, updated_at = ?, version = version + 1
                     WHERE id = ? AND operation_owner IS NOT NULL AND operation_expires_at <= ?
                    """, JdbcSupport.timestamp(nextAttempt(now, retryCount + 1, baseRetryDelay, maxRetryDelay)),
                    JdbcSupport.timestamp(now), id, JdbcSupport.timestamp(now));
            recovered += updated;
        }
        return recovered;
    }

    public int releaseOperation(UUID id, long expectedVersion, String owner, String operationKind,
            int maxAttempts, Duration baseRetryDelay, Duration maxRetryDelay, String failureCode,
            String failureMessage, Instant at) {
        return releaseOperation(id, expectedVersion, owner, operationKind, maxAttempts, baseRetryDelay,
                maxRetryDelay, failureCode, failureMessage, at, 0);
    }

    public int releaseOperation(UUID id, long expectedVersion, String owner, String operationKind,
            int maxAttempts, Duration baseRetryDelay, Duration maxRetryDelay, String failureCode,
            String failureMessage, Instant at, int currentRetryCount) {
        if (maxAttempts <= 0) throw new IllegalArgumentException("maxAttempts must be positive");
        if (currentRetryCount < 0) throw new IllegalArgumentException("currentRetryCount must not be negative");
        validateRetryDelays(baseRetryDelay, maxRetryDelay);
        int updated = jdbc.update("""
                UPDATE task_sandboxes
                   SET status = CASE WHEN retry_count + 1 >= ? THEN 'FAILED' ELSE status END,
                       desired_state = CASE WHEN retry_count + 1 >= ? THEN 'TERMINATED' ELSE desired_state END,
                       failure_code = ?, redacted_failure_message = ?,
                       operation_owner = NULL, operation_expires_at = NULL, operation_kind = NULL,
                       retry_count = retry_count + 1,
                       next_attempt_at = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND operation_owner = ? AND operation_kind = ?
                """, maxAttempts, maxAttempts, failureCode, JdbcSupport.failureMessage(failureMessage),
                JdbcSupport.timestamp(nextAttempt(at, currentRetryCount + 1, baseRetryDelay, maxRetryDelay)),
                JdbcSupport.timestamp(at),
                id, expectedVersion, owner, operationKind);
        ensureUpdated(updated, id, expectedVersion);
        return updated;
    }

    public java.util.List<TaskSandboxRecord> findStopping(int limit) {
        if (limit <= 0 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        return findByStatuses(java.util.List.of(SandboxStatus.STOPPING, SandboxStatus.EXPIRED,
                SandboxStatus.LOST), limit);
    }

    public TaskSandboxRecord markProvisioning(UUID id, long expectedVersion, Instant at) {
        return updateClaimedStatus(id, SandboxStatus.PROVISIONING, expectedVersion, at);
    }

    public TaskSandboxRecord markReady(UUID id, String providerSandboxId, String endpointRef,
            Instant expiresAt, long expectedVersion, Instant at) {
        int updated = jdbc.update("""
                UPDATE task_sandboxes
                   SET provider_sandbox_id = ?, endpoint_ref = ?, status = 'READY', expires_at = ?,
                       last_observed_at = ?, operation_owner = NULL, operation_expires_at = NULL,
                       operation_kind = NULL, retry_count = 0, next_attempt_at = ?,
                       updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND status = 'PROVISIONING'
                """, providerSandboxId, endpointRef, JdbcSupport.timestamp(expiresAt), JdbcSupport.timestamp(at),
                JdbcSupport.timestamp(at), JdbcSupport.timestamp(at), id, expectedVersion);
        ensureUpdated(updated, id, expectedVersion);
        return findById(id).orElseThrow();
    }

    public TaskSandboxRecord markFailed(UUID id, String code, String message,
            long expectedVersion, Instant at) {
        int updated = jdbc.update("""
                UPDATE task_sandboxes
                   SET status = 'FAILED', desired_state = 'TERMINATED', last_observed_at = ?, failure_code = ?,
                       redacted_failure_message = ?, operation_owner = NULL, operation_expires_at = NULL,
                       operation_kind = NULL, retry_count = retry_count + 1, next_attempt_at = ?,
                       updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, JdbcSupport.timestamp(at), code, JdbcSupport.failureMessage(message),
                JdbcSupport.timestamp(at), JdbcSupport.timestamp(at), id, expectedVersion);
        ensureUpdated(updated, id, expectedVersion);
        return findById(id).orElseThrow();
    }

    public TaskSandboxRecord markDestroyed(UUID id, long expectedVersion, Instant at) {
        int updated = jdbc.update("""
                UPDATE task_sandboxes
                   SET status = 'DESTROYED', desired_state = 'TERMINATED', last_observed_at = ?, terminated_at = ?,
                       termination_reason = 'OPERATOR_CLEANUP', operation_owner = NULL,
                       operation_expires_at = NULL, operation_kind = NULL, retry_count = 0,
                       next_attempt_at = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND status IN ('STOPPING', 'EXPIRED', 'LOST')
                """, JdbcSupport.timestamp(at), JdbcSupport.timestamp(at), JdbcSupport.timestamp(at),
                JdbcSupport.timestamp(at), id,
                expectedVersion);
        ensureUpdated(updated, id, expectedVersion);
        return findById(id).orElseThrow();
    }

    public TaskSandboxRecord markExpired(UUID id, long expectedVersion, Instant at) {
        int updated = jdbc.update("""
                UPDATE task_sandboxes
                   SET status = 'EXPIRED', desired_state = 'TERMINATED', last_observed_at = ?, updated_at = ?,
                       version = version + 1
                 WHERE id = ? AND version = ? AND status IN ('PROVISIONING', 'READY', 'RUNNING')
                """, JdbcSupport.timestamp(at), JdbcSupport.timestamp(at), id, expectedVersion);
        ensureUpdated(updated, id, expectedVersion);
        return findById(id).orElseThrow();
    }

    public TaskSandboxRecord markStopping(UUID id, long expectedVersion, Instant at) {
        int updated = jdbc.update("""
                UPDATE task_sandboxes
                   SET status = 'STOPPING', updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND status IN ('EXPIRED', 'LOST')
                """, JdbcSupport.timestamp(at), id, expectedVersion);
        ensureUpdated(updated, id, expectedVersion);
        return findById(id).orElseThrow();
    }

    public Optional<TaskSandboxRecord> findById(UUID id) {
        return query("WHERE id = ?", id).stream().findFirst();
    }

    public Optional<TaskSandboxRecord> findByAttemptId(UUID attemptId) {
        return query("WHERE attempt_id = ?", attemptId).stream().findFirst();
    }

    public Optional<TaskSandboxRecord> findByIdempotencyKey(String idempotencyKey) {
        return query("WHERE idempotency_key = ?", idempotencyKey).stream().findFirst();
    }

    public Optional<TaskSandboxRecord> findByProviderSandboxId(String providerSandboxId) {
        return query("WHERE provider_sandbox_id = ?", providerSandboxId).stream().findFirst();
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM task_sandboxes", Long.class);
        return count == null ? 0 : count;
    }

    public TaskSandboxRecord updateProviderBinding(UUID id, String providerSandboxId, String endpointRef,
            SandboxStatus status, Instant observedAt, long expectedVersion, Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE task_sandboxes
                   SET provider_sandbox_id = ?, endpoint_ref = ?, status = ?, last_observed_at = ?,
                       updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, providerSandboxId, endpointRef, status.name(), JdbcSupport.timestamp(observedAt),
                JdbcSupport.timestamp(updatedAt), id, expectedVersion);
        ensureUpdated(updated, id, expectedVersion);
        return findById(id).orElseThrow();
    }

    public TaskSandboxRecord updateProviderBinding(UUID id, String provider, String providerResourceId,
            String providerResourceUid, SandboxStatus status, String endpointRef, Instant expiresAt,
            long observedGeneration, String workloadUid, String detailsJson, long expectedVersion, Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE task_sandboxes
                   SET provider = ?, provider_sandbox_id = ?, provider_resource_id = ?, provider_resource_uid = ?,
                       status = ?, endpoint_ref = ?, expires_at = ?, observed_generation = ?, workload_uid = ?,
                       details = ?::jsonb, last_observed_at = ?, updated_at = ?, version = version + 1,
                       operation_owner = NULL, operation_expires_at = NULL, operation_kind = NULL,
                       retry_count = 0, next_attempt_at = ?
                 WHERE id = ? AND version = ?
                """, provider, providerResourceId, providerResourceId, providerResourceUid, status.name(), endpointRef,
                JdbcSupport.timestamp(expiresAt), observedGeneration, workloadUid, detailsJson,
                JdbcSupport.timestamp(updatedAt), JdbcSupport.timestamp(updatedAt),
                JdbcSupport.timestamp(updatedAt), id, expectedVersion);
        ensureUpdated(updated, id, expectedVersion);
        return findById(id).orElseThrow();
    }

    public TaskSandboxRecord updateObserved(UUID id, SandboxStatus status, String providerResourceUid,
            String endpointRef, Instant expiresAt, long observedGeneration, String workloadUid,
            String failureCode, String failureMessage, long expectedVersion, Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE task_sandboxes
                   SET provider_resource_uid = COALESCE(?, provider_resource_uid), endpoint_ref = ?,
                       status = ?, expires_at = ?, observed_generation = ?, workload_uid = ?,
                       failure_code = ?, redacted_failure_message = ?, last_observed_at = ?, updated_at = ?,
                       operation_owner = NULL, operation_expires_at = NULL, operation_kind = NULL,
                       retry_count = 0, next_attempt_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND ? >= observed_generation
                """, providerResourceUid, endpointRef, status.name(), JdbcSupport.timestamp(expiresAt),
                observedGeneration, workloadUid, failureCode, JdbcSupport.failureMessage(failureMessage),
                JdbcSupport.timestamp(updatedAt), JdbcSupport.timestamp(updatedAt), JdbcSupport.timestamp(updatedAt),
                id, expectedVersion,
                observedGeneration);
        ensureUpdated(updated, id, expectedVersion);
        return findById(id).orElseThrow();
    }

    public TaskSandboxRecord markDispatched(UUID id, UUID dispatchEventId, Instant at, long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE task_sandboxes SET dispatch_event_id = ?, last_dispatched_at = ?, updated_at = ?,
                       version = version + 1
                 WHERE id = ? AND version = ? AND dispatch_event_id IS NULL
                """, dispatchEventId, JdbcSupport.timestamp(at), id, expectedVersion);
        ensureUpdated(updated, id, expectedVersion);
        return findById(id).orElseThrow();
    }

    public TaskSandboxRecord updateStatus(UUID id, SandboxStatus status, Instant observedAt,
            Instant terminatedAt, SandboxTerminationReason terminationReason, String failureCode,
            String redactedFailureMessage, long expectedVersion, Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE task_sandboxes
                   SET status = ?, last_observed_at = ?, terminated_at = ?, termination_reason = ?,
                       failure_code = ?, redacted_failure_message = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, status.name(), nullableTimestamp(observedAt), nullableTimestamp(terminatedAt),
                nullableReason(terminationReason), failureCode, JdbcSupport.failureMessage(redactedFailureMessage),
                JdbcSupport.timestamp(updatedAt), id, expectedVersion);
        ensureUpdated(updated, id, expectedVersion);
        return findById(id).orElseThrow();
    }

    public TaskSandboxRecord updateExpiry(UUID id, Instant expiresAt, long expectedVersion, Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE task_sandboxes
                   SET expires_at = ?, operation_owner = NULL, operation_expires_at = NULL,
                       operation_kind = NULL, retry_count = 0, next_attempt_at = ?,
                       updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND terminated_at IS NULL
                """, JdbcSupport.timestamp(expiresAt), JdbcSupport.timestamp(updatedAt),
                JdbcSupport.timestamp(updatedAt), id, expectedVersion);
        ensureUpdated(updated, id, expectedVersion);
        return findById(id).orElseThrow();
    }

    private java.util.List<TaskSandboxRecord> query(String predicate, Object argument) {
        return jdbc.query(SELECT_COLUMNS + " " + predicate, this::map, argument);
    }

    private java.util.List<TaskSandboxRecord> findByStatuses(java.util.List<SandboxStatus> statuses, int limit) {
        String placeholders = String.join(",", java.util.Collections.nCopies(statuses.size(), "?"));
        Object[] arguments = new Object[statuses.size() + 1];
        for (int i = 0; i < statuses.size(); i++) {
            arguments[i] = statuses.get(i).name();
        }
        arguments[statuses.size()] = limit;
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE status IN (""" + placeholders + ") ORDER BY updated_at, id LIMIT ?", this::map, arguments);
    }

    private void ensureUpdated(int updated, UUID id, long expectedVersion) {
        if (updated == 0) {
            long actual = jdbc.query("SELECT version FROM task_sandboxes WHERE id = ?",
                    (rs, row) -> rs.getLong(1), id).stream().findFirst().orElse(-1L);
            throw new OptimisticLockFailure("task_sandbox", id, expectedVersion, actual);
        }
    }

    private TaskSandboxRecord updateClaimedStatus(UUID id, SandboxStatus status, long expectedVersion, Instant at) {
        int updated = jdbc.update("""
                UPDATE task_sandboxes
                   SET status = ?, last_observed_at = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND status = 'REQUESTED'
                """, status.name(), JdbcSupport.timestamp(at), JdbcSupport.timestamp(at), id, expectedVersion);
        ensureUpdated(updated, id, expectedVersion);
        return findById(id).orElseThrow();
    }

    private TaskSandboxRecord map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        java.sql.Timestamp lastObserved = rs.getTimestamp("last_observed_at");
        java.sql.Timestamp terminated = rs.getTimestamp("terminated_at");
        String reason = rs.getString("termination_reason");
        java.sql.Timestamp operationExpires = rs.getTimestamp("operation_expires_at");
        java.sql.Timestamp lastDispatched = rs.getTimestamp("last_dispatched_at");
        return new TaskSandboxRecord(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getObject("attempt_id", UUID.class), rs.getString("idempotency_key"),
                rs.getString("provider_sandbox_id"), SandboxProfile.valueOf(rs.getString("profile")),
                SandboxStatus.valueOf(rs.getString("status")), rs.getString("template"),
                rs.getString("endpoint_ref"), JdbcSupport.instant(rs, "requested_at"),
                JdbcSupport.instant(rs, "expires_at"), lastObserved == null ? null : lastObserved.toInstant(),
                terminated == null ? null : terminated.toInstant(),
                reason == null ? null : SandboxTerminationReason.valueOf(reason), rs.getString("failure_code"),
                rs.getString("redacted_failure_message"), JdbcSupport.instant(rs, "created_at"),
                JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"), rs.getString("provider"),
                rs.getString("provider_resource_id"), rs.getString("provider_resource_uid"),
                rs.getLong("observed_generation"), rs.getString("workload_uid"), rs.getString("desired_state"),
                rs.getString("operation_owner"), operationExpires == null ? null : operationExpires.toInstant(),
                rs.getString("operation_kind"), rs.getInt("retry_count"), JdbcSupport.instant(rs, "next_attempt_at"),
                lastDispatched == null ? null : lastDispatched.toInstant(), rs.getObject("dispatch_event_id", UUID.class),
                rs.getString("details"));
    }

    private static java.sql.Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : JdbcSupport.timestamp(value);
    }

    private static String nullableReason(SandboxTerminationReason reason) {
        return reason == null ? null : reason.name();
    }

    private static void requireOperationLease(String owner, Instant operationExpiresAt) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("operation owner must not be blank");
        if (operationExpiresAt == null) throw new IllegalArgumentException("operation expiry must not be null");
    }

    private static void validateRetryDelays(Duration baseRetryDelay, Duration maxRetryDelay) {
        if (baseRetryDelay == null || baseRetryDelay.isNegative() || baseRetryDelay.isZero()) {
            throw new IllegalArgumentException("baseRetryDelay must be positive");
        }
        if (maxRetryDelay == null || maxRetryDelay.compareTo(baseRetryDelay) < 0) {
            throw new IllegalArgumentException("maxRetryDelay must not be shorter than baseRetryDelay");
        }
    }

    private static Instant nextAttempt(Instant at, int attempt, Duration baseRetryDelay, Duration maxRetryDelay) {
        long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 30);
        Duration delay;
        try {
            delay = baseRetryDelay.multipliedBy(multiplier);
        } catch (ArithmeticException overflow) {
            delay = maxRetryDelay;
        }
        if (delay.compareTo(maxRetryDelay) > 0) delay = maxRetryDelay;
        return at.plus(delay);
    }
}
