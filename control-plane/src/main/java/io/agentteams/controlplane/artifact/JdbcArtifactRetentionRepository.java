package io.agentteams.controlplane.artifact;

import io.agentteams.controlplane.persistence.JdbcSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL retention policy and tombstone store. */
@Repository
public class JdbcArtifactRetentionRepository implements ArtifactRetentionRepository {
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcArtifactRetentionRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcArtifactRetentionRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<ArtifactRetentionCandidate> findExpiredCandidates(Instant now, ArtifactRetentionPolicy fallback,
            int limit) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(fallback, "fallback");
        validateLimit(limit);
        return jdbc.query("""
                WITH effective AS (
                    SELECT a.id, a.task_id, a.storage_key, a.created_at,
                           COALESCE(o.successful_task_retention_seconds,
                                p.successful_task_retention_seconds, ?) AS successful_retention_seconds,
                           COALESCE(o.failed_task_retention_seconds,
                                p.failed_task_retention_seconds, ?) AS failed_retention_seconds,
                           COALESCE(o.temporary_upload_retention_seconds,
                                p.temporary_upload_retention_seconds, ?) AS temporary_retention_seconds,
                           COALESCE(o.version, p.version, 0) AS policy_version,
                           CASE
                             WHEN a.status <> 'AVAILABLE' THEN COALESCE(o.temporary_upload_retention_seconds,
                                  p.temporary_upload_retention_seconds, ?)
                             WHEN t.phase = 'SUCCEEDED' THEN COALESCE(o.successful_task_retention_seconds,
                                  p.successful_task_retention_seconds, ?)
                             ELSE COALESCE(o.failed_task_retention_seconds,
                                  p.failed_task_retention_seconds, ?)
                           END AS retention_seconds,
                           COALESCE(o.legal_hold, p.legal_hold, ?) AS legal_hold,
                           CASE WHEN o.task_id IS NOT NULL THEN 'TASK'
                                WHEN p.id IS NOT NULL THEN 'PROJECT' ELSE 'DEFAULT' END AS policy_source
                      FROM artifacts a
                      JOIN tasks t ON t.id = a.task_id
                      LEFT JOIN resource_scopes s ON s.resource_type = 'TASK' AND s.resource_id = a.task_id
                      LEFT JOIN artifact_retention_project_policies p
                        ON p.tenant_id = s.tenant_id AND p.project_id = s.project_id
                      LEFT JOIN artifact_retention_task_overrides o ON o.task_id = a.task_id
                     WHERE a.status <> 'DELETED'
                       AND t.phase IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED')
                       AND NOT EXISTS (
                           SELECT 1 FROM artifact_retention_tombstones tombstone
                            WHERE tombstone.artifact_id = a.id
                       )
                )
                SELECT id, task_id, storage_key, created_at, successful_retention_seconds,
                       failed_retention_seconds, temporary_retention_seconds, retention_seconds,
                       legal_hold, policy_version, policy_source
                  FROM effective
                 WHERE created_at <= (?::timestamptz - (retention_seconds * interval '1 second'))
                 ORDER BY created_at, id
                 LIMIT ?
                """, (rs, row) -> new ArtifactRetentionCandidate(
                rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class), rs.getString("storage_key"),
                JdbcSupport.instant(rs, "created_at"), policy(rs), rs.getLong("policy_version"),
                rs.getString("policy_source")),
                fallback.successfulTaskRetentionSeconds(), fallback.failedTaskRetentionSeconds(),
                fallback.temporaryUploadRetentionSeconds(), fallback.temporaryUploadRetentionSeconds(),
                fallback.successfulTaskRetentionSeconds(), fallback.failedTaskRetentionSeconds(), fallback.legalHold(),
                JdbcSupport.timestamp(now), limit);
    }

    @Override
    public boolean insertTombstone(ArtifactRetentionCandidate candidate, String storageKeyHash, Instant now,
            String status, String policyJson, String operator) {
        Objects.requireNonNull(candidate, "candidate");
        requireText(storageKeyHash, "storageKeyHash");
        Objects.requireNonNull(now, "now");
        requireText(status, "status");
        requireText(policyJson, "policyJson");
        requireText(operator, "operator");
        return jdbc.update("""
                INSERT INTO artifact_retention_tombstones
                    (id, artifact_id, task_id, storage_key_hash, policy, policy_version, status, legal_hold, operator,
                     attempts, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, 0, ?, ?, ?)
                ON CONFLICT (artifact_id) DO NOTHING
                """, UUID.randomUUID(), candidate.artifactId(), candidate.taskId(), storageKeyHash, policyJson,
                candidate.policyVersion(), status, candidate.policy().legalHold(), operator.trim(),
                JdbcSupport.timestamp(now),
                JdbcSupport.timestamp(now), JdbcSupport.timestamp(now)) == 1;
    }

    @Override
    public Optional<ArtifactRetentionProjectPolicy> findProjectPolicy(String tenantId, String projectId) {
        requireText(tenantId, "tenantId");
        requireText(projectId, "projectId");
        return jdbc.query("""
                SELECT id, tenant_id, project_id, successful_task_retention_seconds,
                       failed_task_retention_seconds, temporary_upload_retention_seconds,
                       legal_hold, created_at, updated_at, version
                  FROM artifact_retention_project_policies
                 WHERE tenant_id = ? AND project_id = ?
                """, (rs, row) -> new ArtifactRetentionProjectPolicy(
                rs.getObject("id", UUID.class), rs.getString("tenant_id"), rs.getString("project_id"),
                policy(rs), rs.getLong("version"), JdbcSupport.instant(rs, "created_at"),
                JdbcSupport.instant(rs, "updated_at")), tenantId.trim(), projectId.trim()).stream().findFirst();
    }

    @Override
    public List<ArtifactRetentionTombstone> findDueTombstones(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        validateLimit(limit);
        return jdbc.query("""
                SELECT tombstone.id, tombstone.artifact_id, tombstone.task_id, artifact.storage_key,
                       tombstone.status, tombstone.legal_hold, tombstone.attempts, tombstone.next_attempt_at,
                       tombstone.operator, tombstone.policy_version
                  FROM artifact_retention_tombstones tombstone
                  JOIN artifacts artifact ON artifact.id = tombstone.artifact_id
                 WHERE tombstone.status IN ('PENDING', 'FAILED')
                   AND tombstone.next_attempt_at <= ?
                 ORDER BY tombstone.next_attempt_at, tombstone.id
                 LIMIT ?
                """, (rs, row) -> new ArtifactRetentionTombstone(
                rs.getObject("id", UUID.class), rs.getObject("artifact_id", UUID.class),
                rs.getObject("task_id", UUID.class), rs.getString("storage_key"), rs.getString("status"),
                rs.getBoolean("legal_hold"), rs.getInt("attempts"), JdbcSupport.instant(rs, "next_attempt_at"),
                rs.getString("operator"), rs.getLong("policy_version")),
                JdbcSupport.timestamp(now), limit);
    }

    @Override
    public void markDeleted(UUID tombstoneId, UUID artifactId, Instant now) {
        Objects.requireNonNull(tombstoneId, "tombstoneId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(now, "now");
        jdbc.update("""
                UPDATE artifact_retention_tombstones
                   SET status = 'DELETED', deleted_at = ?, updated_at = ?
                 WHERE id = ? AND status IN ('PENDING', 'FAILED')
                """, JdbcSupport.timestamp(now), JdbcSupport.timestamp(now), tombstoneId);
        jdbc.update("UPDATE artifacts SET status = 'DELETED', updated_at = ? WHERE id = ?",
                JdbcSupport.timestamp(now), artifactId);
    }

    @Override
    public void markHeld(UUID tombstoneId, Instant now) {
        Objects.requireNonNull(tombstoneId, "tombstoneId");
        Objects.requireNonNull(now, "now");
        jdbc.update("""
                UPDATE artifact_retention_tombstones
                   SET status = 'HELD', updated_at = ?
                 WHERE id = ? AND status IN ('PENDING', 'FAILED')
                """, JdbcSupport.timestamp(now), tombstoneId);
    }

    @Override
    public void markFailed(UUID tombstoneId, Instant now, Instant nextAttemptAt, String error) {
        Objects.requireNonNull(tombstoneId, "tombstoneId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        requireText(error, "error");
        jdbc.update("""
                UPDATE artifact_retention_tombstones
                   SET status = 'FAILED', attempts = attempts + 1, next_attempt_at = ?,
                       last_error = ?, updated_at = ?
                 WHERE id = ? AND status IN ('PENDING', 'FAILED')
                """, JdbcSupport.timestamp(nextAttemptAt), error, JdbcSupport.timestamp(now), tombstoneId);
    }

    @Override
    public void upsertProjectPolicy(String tenantId, String projectId, ArtifactRetentionPolicy policy, Instant now) {
        requireText(tenantId, "tenantId");
        requireText(projectId, "projectId");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(now, "now");
        jdbc.update("""
                INSERT INTO artifact_retention_project_policies
                    (id, tenant_id, project_id, successful_task_retention_seconds,
                     failed_task_retention_seconds, temporary_upload_retention_seconds,
                     legal_hold, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT (tenant_id, project_id) DO UPDATE SET
                    successful_task_retention_seconds = EXCLUDED.successful_task_retention_seconds,
                    failed_task_retention_seconds = EXCLUDED.failed_task_retention_seconds,
                    temporary_upload_retention_seconds = EXCLUDED.temporary_upload_retention_seconds,
                    legal_hold = EXCLUDED.legal_hold, updated_at = EXCLUDED.updated_at,
                    version = artifact_retention_project_policies.version + 1
                """, UUID.randomUUID(), tenantId.trim(), projectId.trim(), policy.successfulTaskRetentionSeconds(),
                policy.failedTaskRetentionSeconds(), policy.temporaryUploadRetentionSeconds(), policy.legalHold(),
                JdbcSupport.timestamp(now), JdbcSupport.timestamp(now));
    }

    @Override
    public synchronized ArtifactRetentionProjectPolicy upsertProjectPolicy(String tenantId, String projectId,
            ArtifactRetentionPolicy policy, Instant now, long expectedVersion) {
        requireText(tenantId, "tenantId");
        requireText(projectId, "projectId");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(now, "now");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        int updated = jdbc.update("""
                UPDATE artifact_retention_project_policies
                   SET successful_task_retention_seconds = ?, failed_task_retention_seconds = ?,
                       temporary_upload_retention_seconds = ?, legal_hold = ?, updated_at = ?, version = version + 1
                 WHERE tenant_id = ? AND project_id = ? AND version = ?
                """, policy.successfulTaskRetentionSeconds(), policy.failedTaskRetentionSeconds(),
                policy.temporaryUploadRetentionSeconds(), policy.legalHold(), JdbcSupport.timestamp(now),
                tenantId.trim(), projectId.trim(), expectedVersion);
        if (updated == 1) return findProjectPolicy(tenantId, projectId).orElseThrow();
        if (expectedVersion == 0) {
            int inserted = jdbc.update("""
                    INSERT INTO artifact_retention_project_policies
                        (id, tenant_id, project_id, successful_task_retention_seconds,
                         failed_task_retention_seconds, temporary_upload_retention_seconds,
                         legal_hold, created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    ON CONFLICT (tenant_id, project_id) DO NOTHING
                    """, UUID.randomUUID(), tenantId.trim(), projectId.trim(), policy.successfulTaskRetentionSeconds(),
                    policy.failedTaskRetentionSeconds(), policy.temporaryUploadRetentionSeconds(), policy.legalHold(),
                    JdbcSupport.timestamp(now), JdbcSupport.timestamp(now));
            if (inserted == 1) return findProjectPolicy(tenantId, projectId).orElseThrow();
        }
        throw new ArtifactRetentionPolicyConflictException("artifact retention policy version is stale");
    }

    @Override
    public void upsertTaskOverride(UUID taskId, ArtifactRetentionPolicy policy, Instant now) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(now, "now");
        jdbc.update("""
                INSERT INTO artifact_retention_task_overrides
                    (task_id, successful_task_retention_seconds, failed_task_retention_seconds,
                     temporary_upload_retention_seconds, legal_hold, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT (task_id) DO UPDATE SET
                    successful_task_retention_seconds = EXCLUDED.successful_task_retention_seconds,
                    failed_task_retention_seconds = EXCLUDED.failed_task_retention_seconds,
                    temporary_upload_retention_seconds = EXCLUDED.temporary_upload_retention_seconds,
                    legal_hold = EXCLUDED.legal_hold, updated_at = EXCLUDED.updated_at,
                    version = artifact_retention_task_overrides.version + 1
                """, taskId, policy.successfulTaskRetentionSeconds(), policy.failedTaskRetentionSeconds(),
                policy.temporaryUploadRetentionSeconds(), policy.legalHold(), JdbcSupport.timestamp(now),
                JdbcSupport.timestamp(now));
    }

    private static ArtifactRetentionPolicy policy(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ArtifactRetentionPolicy(Duration.ofSeconds(rs.getLong("successful_retention_seconds")),
                Duration.ofSeconds(rs.getLong("failed_retention_seconds")), Duration.ofSeconds(rs.getLong("temporary_retention_seconds")),
                rs.getBoolean("legal_hold"));
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
