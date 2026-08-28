package io.agentteams.controlplane.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class ModelPriceRepository {

    private final JdbcTemplate jdbc;

    ModelPriceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ModelPriceRecord price) {
        jdbc.update("""
                INSERT INTO model_price_catalog
                    (id, tenant_id, project_id, provider, model, currency,
                     input_price_per_million_tokens, output_price_per_million_tokens,
                     effective_from, effective_to, lifecycle_status, created_at, updated_at,
                     version, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, price.id(), price.tenantId(), price.projectId(), price.provider(), price.model(),
                price.currency(), price.inputPricePerMillionTokens(), price.outputPricePerMillionTokens(),
                JdbcSupport.timestamp(price.effectiveFrom()),
                price.effectiveTo() == null ? null : JdbcSupport.timestamp(price.effectiveTo()),
                price.lifecycleStatus(), JdbcSupport.timestamp(price.createdAt()),
                JdbcSupport.timestamp(price.updatedAt()), price.version(), price.createdBy(), price.updatedBy());
    }

    public Optional<ModelPriceRecord> findById(UUID id, String tenantId, String projectId) {
        return jdbc.query(selectSql() + " WHERE id = ? AND tenant_id = ? AND project_id = ?",
                this::map, id, tenantId, projectId).stream().findFirst();
    }

    public List<ModelPriceRecord> findAll(String tenantId, String projectId) {
        return jdbc.query(selectSql()
                + " WHERE tenant_id = ? AND project_id = ? ORDER BY provider, model, currency, effective_from DESC",
                this::map, tenantId, projectId);
    }

    public Optional<ModelPriceRecord> findEffective(String tenantId, String projectId, String provider,
            String model, String currency, Instant at) {
        return jdbc.query(selectSql() + """
                 WHERE tenant_id = ? AND project_id = ? AND provider = ? AND model = ? AND currency = ?
                   AND lifecycle_status = 'ACTIVE'
                   AND effective_from <= ?
                   AND (effective_to IS NULL OR effective_to > ?)
                 ORDER BY effective_from DESC, version DESC, updated_at DESC
                 LIMIT 1
                """, this::map, tenantId, projectId, provider, model, currency,
                JdbcSupport.timestamp(at), JdbcSupport.timestamp(at)).stream().findFirst();
    }

    public Optional<ModelPriceRecord> findByNaturalKey(String tenantId, String projectId, String provider,
            String model, String currency, Instant effectiveFrom) {
        return jdbc.query(selectSql() + " WHERE tenant_id = ? AND project_id = ? AND provider = ? AND model = ?"
                        + " AND currency = ? AND effective_from = ? LIMIT 1",
                this::map, tenantId, projectId, provider, model, currency, JdbcSupport.timestamp(effectiveFrom))
                .stream().findFirst();
    }

    public Optional<PriceIdempotency> findIdempotency(String tenantId, String projectId, String key) {
        return jdbc.query("""
                SELECT request_hash, price_id
                  FROM model_price_catalog_idempotency
                 WHERE tenant_id = ? AND project_id = ? AND idempotency_key = ?
                """, (rs, row) -> new PriceIdempotency(rs.getString("request_hash"),
                rs.getObject("price_id", UUID.class)), tenantId, projectId, key).stream().findFirst();
    }

    public boolean insertIdempotency(String tenantId, String projectId, String key, String requestHash,
            UUID priceId, Instant createdAt) {
        return jdbc.update("""
                INSERT INTO model_price_catalog_idempotency
                    (tenant_id, project_id, idempotency_key, request_hash, price_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, project_id, idempotency_key) DO NOTHING
                """, tenantId, projectId, key, requestHash, priceId, JdbcSupport.timestamp(createdAt)) == 1;
    }

    public ModelPriceRecord updateLifecycle(UUID id, String tenantId, String projectId,
            String lifecycleStatus, long expectedVersion, Instant updatedAt, String updatedBy) {
        int updated = jdbc.update("""
                UPDATE model_price_catalog
                   SET lifecycle_status = ?, updated_at = ?, updated_by = ?, version = version + 1
                 WHERE id = ? AND tenant_id = ? AND project_id = ? AND version = ?
                """, lifecycleStatus, JdbcSupport.timestamp(updatedAt), updatedBy, id, tenantId, projectId,
                expectedVersion);
        if (updated == 0) {
            long actual = jdbc.query("""
                    SELECT version FROM model_price_catalog
                     WHERE id = ? AND tenant_id = ? AND project_id = ?
                    """, (rs, row) -> rs.getLong(1), id, tenantId, projectId).stream()
                    .findFirst().orElse(-1L);
            throw new OptimisticLockFailure("model_price", id, expectedVersion, actual);
        }
        return findById(id, tenantId, projectId).orElseThrow();
    }

    private static String selectSql() {
        return """
                SELECT id, tenant_id, project_id, provider, model, currency,
                       input_price_per_million_tokens, output_price_per_million_tokens,
                       effective_from, effective_to, lifecycle_status, created_at, updated_at,
                       version, created_by, updated_by
                  FROM model_price_catalog
                """;
    }

    private ModelPriceRecord map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        java.sql.Timestamp effectiveTo = rs.getTimestamp("effective_to");
        return new ModelPriceRecord(rs.getObject("id", UUID.class), rs.getString("tenant_id"),
                rs.getString("project_id"), rs.getString("provider"), rs.getString("model"),
                rs.getString("currency"), rs.getBigDecimal("input_price_per_million_tokens"),
                rs.getBigDecimal("output_price_per_million_tokens"), JdbcSupport.instant(rs, "effective_from"),
                effectiveTo == null ? null : effectiveTo.toInstant(), rs.getString("lifecycle_status"),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"),
                rs.getLong("version"), rs.getString("created_by"), rs.getString("updated_by"));
    }

    public record PriceIdempotency(String requestHash, UUID priceId) { }
}
