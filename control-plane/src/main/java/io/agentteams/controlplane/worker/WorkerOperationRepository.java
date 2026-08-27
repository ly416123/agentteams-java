package io.agentteams.controlplane.worker;

import io.agentteams.controlplane.persistence.JdbcSupport;
import io.agentteams.controlplane.persistence.OptimisticLockFailure;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class WorkerOperationRepository {

    private final JdbcTemplate jdbc;

    public WorkerOperationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(WorkerOperation operation) {
        jdbc.update("""
                INSERT INTO worker_operations
                    (id, agent_id, type, status, requested_spec_digest, requested_runtime,
                     requested_config_revision, requested_secret_generation, previous_stable_spec,
                     idempotency_key, expected_agent_version, owner, lease_expires_at, failure_category,
                     correlation_id, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, operation.id(), operation.agentId(), operation.type().name(), operation.status().name(),
                operation.requestedSpecDigest(), operation.requestedRuntime(), operation.requestedConfigRevision(),
                operation.requestedSecretGeneration(), JdbcSupport.jsonSnapshot(operation.previousStableSpec()),
                operation.idempotencyKey(), operation.expectedAgentVersion(), operation.owner(),
                timestamp(operation.leaseExpiresAt()), operation.failureCategory(), operation.correlationId(),
                JdbcSupport.timestamp(operation.createdAt()), JdbcSupport.timestamp(operation.updatedAt()),
                operation.version());
    }

    public Optional<WorkerOperation> findById(UUID id) {
        return jdbc.query(select() + " WHERE id = ?", this::map, id).stream().findFirst();
    }

    public Optional<WorkerOperation> findByIdForUpdate(UUID id) {
        return jdbc.query(select() + " WHERE id = ? FOR UPDATE", this::map, id).stream().findFirst();
    }

    public Optional<WorkerOperation> findByAgentAndIdempotencyKey(UUID agentId, String idempotencyKey) {
        return jdbc.query(select() + " WHERE agent_id = ? AND idempotency_key = ?", this::map,
                agentId, idempotencyKey).stream().findFirst();
    }

    public Optional<WorkerOperation> findActiveByAgentForUpdate(UUID agentId) {
        return jdbc.query(select() + " WHERE agent_id = ? AND status IN ('PENDING', 'RUNNING')"
                        + " ORDER BY created_at, id LIMIT 1 FOR UPDATE",
                this::map, agentId).stream().findFirst();
    }

    public Optional<WorkerOperation> findActiveByAgentForUpdate(UUID agentId, Instant now) {
        return jdbc.query(select() + " WHERE agent_id = ? AND status IN ('PENDING', 'RUNNING')"
                        + " AND lease_expires_at > ? ORDER BY created_at, id LIMIT 1 FOR UPDATE",
                this::map, agentId, JdbcSupport.timestamp(now)).stream().findFirst();
    }

    public Optional<WorkerOperation> findActiveByAgent(UUID agentId, Instant now) {
        return jdbc.query(select() + " WHERE agent_id = ? AND type = 'ROLLOUT'"
                        + " AND status IN ('PENDING', 'RUNNING')"
                        + " AND lease_expires_at > ? ORDER BY created_at, id LIMIT 1",
                this::map, agentId, JdbcSupport.timestamp(now)).stream().findFirst();
    }

    public Optional<WorkerOperation> findFailedRolloutByAgent(UUID agentId, Instant now) {
        return jdbc.query(select() + " WHERE agent_id = ? AND type = 'ROLLOUT'"
                        + " AND status = 'FAILED' ORDER BY updated_at, id LIMIT 1",
                this::map, agentId).stream().findFirst();
    }

    public List<WorkerOperation> findExpiredForUpdate(Instant now) {
        return jdbc.query(select() + " WHERE status IN ('PENDING', 'RUNNING')"
                        + " AND lease_expires_at <= ? ORDER BY lease_expires_at, id FOR UPDATE SKIP LOCKED",
                this::map, JdbcSupport.timestamp(now));
    }

    public WorkerOperation updateStatus(UUID id, WorkerOperationStatus status, String failureCategory,
            long expectedVersion, Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE worker_operations
                   SET status = ?, failure_category = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, status.name(), failureCategory, JdbcSupport.timestamp(updatedAt), id, expectedVersion);
        if (updated == 0) {
            throw new OptimisticLockFailure("worker_operation", id, expectedVersion, actualVersion(id));
        }
        return findById(id).orElseThrow();
    }

    public void recordOperatorObservation(UUID operationId, boolean ready, String specDigest, String runtime,
            String configRevision, String secretGeneration, Instant observedAt) {
        jdbc.update("""
                INSERT INTO worker_operation_observations
                    (operation_id, operator_ready, operator_spec_digest, operator_runtime,
                     operator_config_revision, operator_secret_generation, operator_observed_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (operation_id) DO UPDATE SET
                    operator_ready = EXCLUDED.operator_ready,
                    operator_spec_digest = EXCLUDED.operator_spec_digest,
                    operator_runtime = EXCLUDED.operator_runtime,
                    operator_config_revision = EXCLUDED.operator_config_revision,
                    operator_secret_generation = EXCLUDED.operator_secret_generation,
                    operator_observed_at = EXCLUDED.operator_observed_at,
                    updated_at = EXCLUDED.updated_at
                """, operationId, ready, text(specDigest), text(runtime), text(configRevision),
                text(secretGeneration), JdbcSupport.timestamp(observedAt), JdbcSupport.timestamp(observedAt));
    }

    public void recordGatewayObservation(UUID operationId, boolean online, String specDigest, String runtime,
            String configRevision, String secretGeneration, Instant observedAt) {
        jdbc.update("""
                INSERT INTO worker_operation_observations
                    (operation_id, gateway_online, gateway_spec_digest, gateway_runtime,
                     gateway_config_revision, gateway_secret_generation, gateway_observed_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (operation_id) DO UPDATE SET
                    gateway_online = EXCLUDED.gateway_online,
                    gateway_spec_digest = EXCLUDED.gateway_spec_digest,
                    gateway_runtime = EXCLUDED.gateway_runtime,
                    gateway_config_revision = EXCLUDED.gateway_config_revision,
                    gateway_secret_generation = EXCLUDED.gateway_secret_generation,
                    updated_at = EXCLUDED.updated_at
                """, operationId, online, text(specDigest), text(runtime), text(configRevision),
                text(secretGeneration), JdbcSupport.timestamp(observedAt), JdbcSupport.timestamp(observedAt));
    }

    public Optional<WorkerOperationObservation> findObservation(UUID operationId) {
        return jdbc.query("""
                SELECT operation_id, operator_ready, operator_spec_digest, operator_runtime,
                       operator_config_revision, operator_secret_generation, operator_observed_at,
                       gateway_online, gateway_spec_digest, gateway_runtime, gateway_config_revision,
                       gateway_secret_generation, gateway_observed_at, updated_at
                  FROM worker_operation_observations WHERE operation_id = ?
                """, (rs, row) -> new WorkerOperationObservation(
                        rs.getObject("operation_id", UUID.class), rs.getBoolean("operator_ready"),
                        rs.getString("operator_spec_digest"), rs.getString("operator_runtime"),
                        rs.getString("operator_config_revision"), rs.getString("operator_secret_generation"),
                        instant(rs.getTimestamp("operator_observed_at")), rs.getBoolean("gateway_online"),
                        rs.getString("gateway_spec_digest"), rs.getString("gateway_runtime"),
                        rs.getString("gateway_config_revision"), rs.getString("gateway_secret_generation"),
                        instant(rs.getTimestamp("gateway_observed_at")), JdbcSupport.instant(rs, "updated_at")),
                operationId).stream().findFirst();
    }

    private long actualVersion(UUID id) {
        return jdbc.query("SELECT version FROM worker_operations WHERE id = ?", (rs, row) -> rs.getLong(1), id)
                .stream().findFirst().orElse(-1L);
    }

    private static String select() {
        return """
                SELECT id, agent_id, type, status, requested_spec_digest, requested_runtime,
                       requested_config_revision, requested_secret_generation, previous_stable_spec::text,
                       idempotency_key, expected_agent_version, owner, lease_expires_at, failure_category,
                       correlation_id, created_at, updated_at, version
                  FROM worker_operations
                """;
    }

    private WorkerOperation map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new WorkerOperation(rs.getObject("id", UUID.class), rs.getObject("agent_id", UUID.class),
                WorkerOperationType.valueOf(rs.getString("type")),
                WorkerOperationStatus.valueOf(rs.getString("status")), rs.getString("requested_spec_digest"),
                rs.getString("requested_runtime"), rs.getString("requested_config_revision"),
                rs.getString("requested_secret_generation"), rs.getString("previous_stable_spec"),
                rs.getString("idempotency_key"),
                rs.getLong("expected_agent_version"), rs.getString("owner"),
                instant(rs.getTimestamp("lease_expires_at")), rs.getString("failure_category"),
                rs.getString("correlation_id"), JdbcSupport.instant(rs, "created_at"),
                JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : JdbcSupport.timestamp(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
