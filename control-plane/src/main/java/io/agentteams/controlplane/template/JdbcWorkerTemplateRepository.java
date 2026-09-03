package io.agentteams.controlplane.template;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcWorkerTemplateRepository implements WorkerTemplateRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcWorkerTemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(
                java.util.Objects.requireNonNull(jdbc.getDataSource(), "jdbc data source")));
    }

    @Override
    public boolean insertIdempotency(String key, String requestHash, UUID templateId, Instant createdAt) {
        return jdbc.update("""
                INSERT INTO worker_template_create_idempotency(idempotency_key, request_hash, template_id, created_at)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, key, requestHash, templateId, java.sql.Timestamp.from(createdAt)) == 1;
    }

    @Override
    public Optional<IdempotencyRecord> findIdempotency(String key) {
        return jdbc.query("""
                SELECT idempotency_key, request_hash, template_id, created_at
                  FROM worker_template_create_idempotency WHERE idempotency_key = ?
                """, (rs, row) -> new IdempotencyRecord(rs.getString(1), rs.getString(2),
                rs.getObject(3, UUID.class), rs.getTimestamp(4).toInstant()), key).stream().findFirst();
    }

    @Override
    public void insertTemplate(WorkerTemplate template) {
        jdbc.update("""
                INSERT INTO worker_templates(id, tenant_id, project_id, name, display_name,
                    current_published_revision, version, created_at, updated_at, worker_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, template.id(), template.tenantId(), template.projectId(), template.name(), template.displayName(),
                template.currentPublishedRevision(), template.version(), ts(template.createdAt()), ts(template.updatedAt()),
                template.workerType().name());
    }

    @Override
    public List<WorkerTemplate> findTemplates(String tenantId, String projectId) {
        return jdbc.query("""
                SELECT id, tenant_id, project_id, name, display_name, current_published_revision,
                       version, created_at, updated_at, worker_type
                  FROM worker_templates WHERE tenant_id = ? AND project_id = ? ORDER BY name, id
                """, this::mapTemplate, tenantId, projectId);
    }

    @Override
    public Optional<WorkerTemplate> findTemplate(UUID templateId) {
        return jdbc.query("""
                SELECT id, tenant_id, project_id, name, display_name, current_published_revision,
                       version, created_at, updated_at, worker_type FROM worker_templates WHERE id = ?
                """, this::mapTemplate, templateId).stream().findFirst();
    }

    @Override
    public long nextRevision(UUID templateId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(revision), 0) + 1 FROM worker_template_revisions WHERE template_id = ?",
                Long.class, templateId);
        return value == null ? 1 : value;
    }

    @Override
    public WorkerTemplateRevision createRevision(UUID templateId, long revision, String specJson, String digest,
            String actor, Instant now, String idempotencyKey) {
        String requestHash = hash(specJson + "\u0000" + digest + "\u0000" + actor);
        return transaction.execute(status -> {
            Optional<WorkerTemplateRevision> replay = findRevisionByKey(templateId, idempotencyKey);
            if (replay.isPresent()) {
                String storedHash = jdbc.queryForObject("""
                        SELECT request_hash FROM worker_template_revisions
                         WHERE template_id = ? AND idempotency_key = ?
                        """, String.class, templateId, idempotencyKey);
                if (!requestHash.equals(storedHash)) {
                    throw new TemplateConflictException("idempotency key request mismatch");
                }
                return replay.get();
            }
            String workerType = jdbc.queryForObject("SELECT worker_type FROM worker_templates WHERE id = ?",
                    String.class, templateId);
            jdbc.update("""
                    INSERT INTO worker_template_revisions(template_id, revision, spec, digest, status, created_by,
                        created_at, updated_at, version, idempotency_key, request_hash, worker_type)
                    VALUES (?, ?, CAST(? AS jsonb), ?, 'DRAFT', ?, ?, ?, 0, ?, ?, ?)
                    """, templateId, revision, specJson, digest, actor, ts(now), ts(now), idempotencyKey, requestHash,
                    workerType);
            return findRevision(templateId, revision).orElseThrow();
        });
    }

    @Override
    public Optional<WorkerTemplateRevision> findRevision(UUID templateId, long revision) {
        return jdbc.query("""
                SELECT template_id, revision, spec::text, digest, status, created_by, created_at, updated_at, version,
                       worker_type
                  FROM worker_template_revisions WHERE template_id = ? AND revision = ?
                """, this::mapRevision, templateId, revision).stream().findFirst();
    }

    @Override
    public List<WorkerTemplateRevision> findRevisions(UUID templateId) {
        return jdbc.query("""
                SELECT template_id, revision, spec::text, digest, status, created_by, created_at, updated_at, version,
                       worker_type
                  FROM worker_template_revisions WHERE template_id = ? ORDER BY revision
                """, this::mapRevision, templateId);
    }

    @Override
    public WorkerTemplateRevision transition(UUID templateId, long revision, long expectedVersion,
            TemplateStatus expected, TemplateStatus next, String idempotencyKey) {
        String requestHash = hash("transition\u0000" + revision + "\u0000" + expectedVersion + "\u0000"
                + expected + "\u0000" + next);
        return transaction.execute(status -> {
            Optional<WorkerTemplateRevision> replay = operationResult(templateId, revision, "REVIEW", idempotencyKey,
                    requestHash);
            if (replay.isPresent()) return replay.get();
            WorkerTemplateRevision current = lockedRevision(templateId, revision);
            if (current.status() != expected || current.version() != expectedVersion) {
                throw new TemplateConflictException("template revision version or status is stale");
            }
            int updated = jdbc.update("""
                    UPDATE worker_template_revisions SET status = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                     WHERE template_id = ? AND revision = ? AND status = ? AND version = ?
                    """, next.name(), templateId, revision, expected.name(), expectedVersion);
            if (updated != 1) throw new TemplateConflictException("template revision version is stale");
            recordOperation(templateId, revision, "REVIEW", idempotencyKey, requestHash);
            return findRevision(templateId, revision).orElseThrow();
        });
    }

    @Override
    public WorkerTemplateRevision publish(UUID templateId, long revision, long expectedVersion, String idempotencyKey) {
        String requestHash = hash("publish\u0000" + revision + "\u0000" + expectedVersion);
        return transaction.execute(status -> {
            Optional<WorkerTemplateRevision> replay = operationResult(templateId, revision, "PUBLISH", idempotencyKey,
                    requestHash);
            if (replay.isPresent()) return replay.get();
            WorkerTemplateRevision current = lockedRevision(templateId, revision);
            if ((current.status() != TemplateStatus.DRAFT && current.status() != TemplateStatus.REVIEWING)
                    || current.version() != expectedVersion) {
                throw new TemplateConflictException("template revision version or status is stale");
            }
            jdbc.update("""
                    UPDATE worker_template_revisions SET status = 'DEPRECATED', version = version + 1
                     WHERE template_id = ? AND status = 'PUBLISHED'
                    """, templateId);
            jdbc.update("""
                    UPDATE worker_template_revisions SET status = 'PUBLISHED', updated_at = CURRENT_TIMESTAMP,
                        version = version + 1 WHERE template_id = ? AND revision = ? AND version = ?
                    """, templateId, revision, expectedVersion);
            jdbc.update("""
                    UPDATE worker_templates SET current_published_revision = ?, version = version + 1,
                        updated_at = CURRENT_TIMESTAMP WHERE id = ?
                    """, revision, templateId);
            recordOperation(templateId, revision, "PUBLISH", idempotencyKey, requestHash);
            return findRevision(templateId, revision).orElseThrow();
        });
    }

    @Override
    public Optional<WorkerTemplateInstance> findInstanceByIdempotency(UUID templateId, String idempotencyKey) {
        return jdbc.query(instanceSql() + " WHERE template_id = ? AND idempotency_key = ?", this::mapInstance,
                templateId, idempotencyKey).stream().findFirst();
    }

    @Override
    public WorkerTemplateInstance insertInstance(WorkerTemplateInstance instance) {
        try {
            jdbc.update("""
                    INSERT INTO worker_template_instances(id, template_id, template_revision, agent_spec_id, worker_id,
                        status, current_template_revision, idempotency_key, request_hash, created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, instance.id(), instance.templateId(), instance.templateRevision(), instance.agentSpecId(),
                    instance.workerId(), instance.status(), instance.currentTemplateRevision(), instance.idempotencyKey(),
                    instance.requestHash(), ts(instance.createdAt()), ts(instance.updatedAt()), instance.version());
            return instance;
        } catch (DuplicateKeyException error) {
            return findInstanceByIdempotency(instance.templateId(), instance.idempotencyKey()).orElseThrow(() -> error);
        }
    }

    @Override
    public WorkerTemplateInstance updateInstance(WorkerTemplateInstance instance, long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE worker_template_instances SET agent_spec_id = ?, worker_id = ?, status = ?,
                    current_template_revision = ?, updated_at = ?, version = version + 1
                 WHERE template_id = ? AND id = ? AND version = ?
                """, instance.agentSpecId(), instance.workerId(), instance.status(), instance.currentTemplateRevision(),
                ts(instance.updatedAt()), instance.templateId(), instance.id(), expectedVersion);
        if (updated != 1) throw new TemplateConflictException("worker template instance version is stale");
        return findInstance(instance.templateId(), instance.id()).orElseThrow();
    }

    @Override
    public WorkerTemplateInstance upgradeInstance(WorkerTemplateInstance instance, long expectedVersion,
            long targetRevision, String idempotencyKey, String requestHash) {
        return transaction.execute(status -> {
            Optional<WorkerTemplateRevision> operation = operationResult(instance.templateId(), targetRevision,
                    "UPGRADE", idempotencyKey, requestHash);
            if (operation.isPresent()) return findInstance(instance.templateId(), instance.id()).orElseThrow();
            int updated = jdbc.update("""
                    UPDATE worker_template_instances SET agent_spec_id = ?, worker_id = ?, status = 'SUCCEEDED',
                        current_template_revision = ?, updated_at = ?, version = version + 1
                     WHERE template_id = ? AND id = ? AND version = ?
                    """, instance.agentSpecId(), instance.workerId(), targetRevision, ts(instance.updatedAt()),
                    instance.templateId(), instance.id(), expectedVersion);
            if (updated != 1) throw new TemplateConflictException("worker template instance version is stale");
            recordOperation(instance.templateId(), targetRevision, "UPGRADE", idempotencyKey, requestHash);
            return findInstance(instance.templateId(), instance.id()).orElseThrow();
        });
    }

    @Override
    public Optional<WorkerTemplateInstance> findInstance(UUID templateId, UUID instanceId) {
        return jdbc.query(instanceSql() + " WHERE template_id = ? AND id = ?", this::mapInstance,
                templateId, instanceId).stream().findFirst();
    }

    @Override
    public List<WorkerTemplateInstance> findInstances(UUID templateId) {
        return jdbc.query(instanceSql() + " WHERE template_id = ? ORDER BY created_at, id", this::mapInstance, templateId);
    }

    private Optional<WorkerTemplateRevision> findRevisionByKey(UUID templateId, String key) {
        return jdbc.query("""
                SELECT template_id, revision, spec::text, digest, status, created_by, created_at, updated_at, version,
                       worker_type
                  FROM worker_template_revisions WHERE template_id = ? AND idempotency_key = ?
                """, this::mapRevision, templateId, key).stream().findFirst();
    }

    private Optional<WorkerTemplateRevision> operationResult(UUID templateId, long revision, String operation,
            String key, String requestHash) {
        List<String> hashes = jdbc.query("""
                SELECT request_hash FROM worker_template_operations
                 WHERE template_id = ? AND revision = ? AND operation = ? AND idempotency_key = ?
                """, (rs, row) -> rs.getString(1), templateId, revision, operation, key);
        if (hashes.isEmpty()) return Optional.empty();
        if (!requestHash.equals(hashes.get(0))) throw new TemplateConflictException("idempotency key request mismatch");
        return findRevision(templateId, revision);
    }

    private void recordOperation(UUID templateId, long revision, String operation, String key, String requestHash) {
        jdbc.update("""
                INSERT INTO worker_template_operations(template_id, revision, operation, idempotency_key,
                    request_hash, created_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, templateId, revision, operation, key, requestHash);
    }

    private WorkerTemplateRevision lockedRevision(UUID templateId, long revision) {
        return jdbc.query("""
                SELECT template_id, revision, spec::text, digest, status, created_by, created_at, updated_at, version,
                       worker_type
                  FROM worker_template_revisions WHERE template_id = ? AND revision = ? FOR UPDATE
                """, this::mapRevision, templateId, revision).stream().findFirst()
                .orElseThrow(() -> new TemplateConflictException("worker template revision does not exist"));
    }

    private WorkerTemplate mapTemplate(ResultSet rs, int row) throws SQLException {
        return new WorkerTemplate(rs.getObject("id", UUID.class), rs.getString("tenant_id"), rs.getString("project_id"),
                rs.getString("name"), rs.getString("display_name"), io.agentteams.domain.agent.WorkerType.valueOf(rs.getString("worker_type")),
                (Long) rs.getObject("current_published_revision"),
                rs.getLong("version"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }

    private WorkerTemplateRevision mapRevision(ResultSet rs, int row) throws SQLException {
        return new WorkerTemplateRevision(rs.getObject("template_id", UUID.class), rs.getLong("revision"),
                rs.getString("spec"), rs.getString("digest"), io.agentteams.domain.agent.WorkerType.valueOf(rs.getString("worker_type")),
                TemplateStatus.valueOf(rs.getString("status")),
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"));
    }

    private WorkerTemplateInstance mapInstance(ResultSet rs, int row) throws SQLException {
        return new WorkerTemplateInstance(rs.getObject("id", UUID.class), rs.getObject("template_id", UUID.class),
                rs.getLong("template_revision"), rs.getObject("agent_spec_id", UUID.class), rs.getObject("worker_id", UUID.class),
                rs.getString("status"), rs.getLong("current_template_revision"), rs.getString("idempotency_key"),
                rs.getString("request_hash"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"));
    }

    private static String instanceSql() {
        return """
                SELECT id, template_id, template_revision, agent_spec_id, worker_id, status,
                current_template_revision, idempotency_key, request_hash, created_at, updated_at, version
                  FROM worker_template_instances""";
    }

    private static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static java.sql.Timestamp ts(Instant value) { return java.sql.Timestamp.from(value); }
}
