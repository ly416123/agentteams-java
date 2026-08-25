package io.agentteams.controlplane.persistence;

import io.agentteams.application.api.SandboxProfile;
import io.agentteams.application.api.SandboxStatus;
import io.agentteams.application.api.SandboxTerminationReason;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class TaskSandboxRepository {

    private final JdbcTemplate jdbc;

    TaskSandboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TaskSandboxRecord sandbox) {
        jdbc.update("""
                INSERT INTO task_sandboxes
                    (id, task_id, attempt_id, idempotency_key, provider_sandbox_id, profile, status,
                     template, endpoint_ref, requested_at, expires_at, last_observed_at, terminated_at,
                     termination_reason, failure_code, redacted_failure_message, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, sandbox.id(), sandbox.taskId(), sandbox.attemptId(), sandbox.idempotencyKey(),
                sandbox.providerSandboxId(), sandbox.profile().name(), sandbox.status().name(), sandbox.template(),
                sandbox.endpointRef(), JdbcSupport.timestamp(sandbox.requestedAt()),
                JdbcSupport.timestamp(sandbox.expiresAt()), nullableTimestamp(sandbox.lastObservedAt()),
                nullableTimestamp(sandbox.terminatedAt()), nullableReason(sandbox.terminationReason()),
                sandbox.failureCode(), JdbcSupport.failureMessage(sandbox.redactedFailureMessage()),
                JdbcSupport.timestamp(sandbox.createdAt()), JdbcSupport.timestamp(sandbox.updatedAt()),
                sandbox.version());
    }

    public boolean insertIfAbsent(TaskSandboxRecord sandbox) {
        int inserted = jdbc.update("""
                INSERT INTO task_sandboxes
                    (id, task_id, attempt_id, idempotency_key, provider_sandbox_id, profile, status,
                     template, endpoint_ref, requested_at, expires_at, last_observed_at, terminated_at,
                     termination_reason, failure_code, redacted_failure_message, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (attempt_id) DO NOTHING
                """, sandbox.id(), sandbox.taskId(), sandbox.attemptId(), sandbox.idempotencyKey(),
                sandbox.providerSandboxId(), sandbox.profile().name(), sandbox.status().name(), sandbox.template(),
                sandbox.endpointRef(), JdbcSupport.timestamp(sandbox.requestedAt()),
                JdbcSupport.timestamp(sandbox.expiresAt()), nullableTimestamp(sandbox.lastObservedAt()),
                nullableTimestamp(sandbox.terminatedAt()), nullableReason(sandbox.terminationReason()),
                sandbox.failureCode(), JdbcSupport.failureMessage(sandbox.redactedFailureMessage()),
                JdbcSupport.timestamp(sandbox.createdAt()), JdbcSupport.timestamp(sandbox.updatedAt()),
                sandbox.version());
        return inserted == 1;
    }

    public java.util.List<TaskSandboxRecord> claimRequested(Instant now, int limit) {
        if (limit <= 0 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        return jdbc.query("""
                SELECT id, task_id, attempt_id, idempotency_key, provider_sandbox_id, profile, status,
                       template, endpoint_ref, requested_at, expires_at, last_observed_at, terminated_at,
                       termination_reason, failure_code, redacted_failure_message, created_at, updated_at, version
                  FROM task_sandboxes
                 WHERE status = 'REQUESTED' AND expires_at > ?
                 ORDER BY requested_at, id
                 FOR UPDATE SKIP LOCKED
                 LIMIT ?
                """, this::map, JdbcSupport.timestamp(now), limit);
    }

    public java.util.List<TaskSandboxRecord> findRenewable(int limit) {
        if (limit <= 0 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        return findByStatuses(java.util.List.of(SandboxStatus.READY, SandboxStatus.RUNNING), limit);
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
                       last_observed_at = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND status = 'PROVISIONING'
                """, providerSandboxId, endpointRef, JdbcSupport.timestamp(expiresAt), JdbcSupport.timestamp(at),
                JdbcSupport.timestamp(at), id, expectedVersion);
        ensureUpdated(updated, id, expectedVersion);
        return findById(id).orElseThrow();
    }

    public TaskSandboxRecord markFailed(UUID id, String code, String message,
            long expectedVersion, Instant at) {
        int updated = jdbc.update("""
                UPDATE task_sandboxes
                   SET status = 'FAILED', last_observed_at = ?, failure_code = ?,
                       redacted_failure_message = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, JdbcSupport.timestamp(at), code, JdbcSupport.failureMessage(message),
                JdbcSupport.timestamp(at), id, expectedVersion);
        ensureUpdated(updated, id, expectedVersion);
        return findById(id).orElseThrow();
    }

    public TaskSandboxRecord markDestroyed(UUID id, long expectedVersion, Instant at) {
        int updated = jdbc.update("""
                UPDATE task_sandboxes
                   SET status = 'DESTROYED', last_observed_at = ?, terminated_at = ?,
                       termination_reason = 'OPERATOR_CLEANUP', updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND status IN ('STOPPING', 'EXPIRED', 'LOST')
                """, JdbcSupport.timestamp(at), JdbcSupport.timestamp(at), JdbcSupport.timestamp(at), id,
                expectedVersion);
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
                   SET expires_at = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND terminated_at IS NULL
                """, JdbcSupport.timestamp(expiresAt), JdbcSupport.timestamp(updatedAt), id, expectedVersion);
        ensureUpdated(updated, id, expectedVersion);
        return findById(id).orElseThrow();
    }

    private java.util.List<TaskSandboxRecord> query(String predicate, Object argument) {
        return jdbc.query("""
                SELECT id, task_id, attempt_id, idempotency_key, provider_sandbox_id, profile, status,
                       template, endpoint_ref, requested_at, expires_at, last_observed_at, terminated_at,
                       termination_reason, failure_code, redacted_failure_message, created_at, updated_at, version
                  FROM task_sandboxes """ + predicate, this::map, argument);
    }

    private java.util.List<TaskSandboxRecord> findByStatuses(java.util.List<SandboxStatus> statuses, int limit) {
        String placeholders = String.join(",", java.util.Collections.nCopies(statuses.size(), "?"));
        Object[] arguments = new Object[statuses.size() + 1];
        for (int i = 0; i < statuses.size(); i++) {
            arguments[i] = statuses.get(i).name();
        }
        arguments[statuses.size()] = limit;
        return jdbc.query("""
                SELECT id, task_id, attempt_id, idempotency_key, provider_sandbox_id, profile, status,
                       template, endpoint_ref, requested_at, expires_at, last_observed_at, terminated_at,
                       termination_reason, failure_code, redacted_failure_message, created_at, updated_at, version
                  FROM task_sandboxes
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
        return new TaskSandboxRecord(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getObject("attempt_id", UUID.class), rs.getString("idempotency_key"),
                rs.getString("provider_sandbox_id"), SandboxProfile.valueOf(rs.getString("profile")),
                SandboxStatus.valueOf(rs.getString("status")), rs.getString("template"),
                rs.getString("endpoint_ref"), JdbcSupport.instant(rs, "requested_at"),
                JdbcSupport.instant(rs, "expires_at"), lastObserved == null ? null : lastObserved.toInstant(),
                terminated == null ? null : terminated.toInstant(),
                reason == null ? null : SandboxTerminationReason.valueOf(reason), rs.getString("failure_code"),
                rs.getString("redacted_failure_message"), JdbcSupport.instant(rs, "created_at"),
                JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static java.sql.Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : JdbcSupport.timestamp(value);
    }

    private static String nullableReason(SandboxTerminationReason reason) {
        return reason == null ? null : reason.name();
    }
}
