package io.agentteams.controlplane.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class IdempotencyKeyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IdempotencyKeyRecord> findByKey(String key) {
        return jdbc.query("""
                SELECT id, idempotency_key, operation, request_hash, resource_type, resource_id,
                       response_payload::text, created_at, updated_at, version
                  FROM idempotency_keys WHERE idempotency_key = ?
                """, this::map, key).stream().findFirst();
    }

    public boolean insertIfAbsent(IdempotencyKeyRecord record) {
        return jdbc.update("""
                INSERT INTO idempotency_keys
                    (id, idempotency_key, operation, request_hash, resource_type, resource_id,
                     response_payload, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, record.id(), record.idempotencyKey(), record.operation(), record.requestHash(),
                record.resourceType(), record.resourceId(), JdbcSupport.json(record.responsePayloadJson()),
                JdbcSupport.timestamp(record.createdAt()), JdbcSupport.timestamp(record.updatedAt()),
                record.version()) == 1;
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Long.class);
        return count == null ? 0 : count;
    }

    private IdempotencyKeyRecord map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new IdempotencyKeyRecord(rs.getObject("id", UUID.class), rs.getString("idempotency_key"),
                rs.getString("operation"), rs.getString("request_hash"), rs.getString("resource_type"),
                rs.getObject("resource_id", UUID.class), rs.getString("response_payload"),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"),
                rs.getLong("version"));
    }
}
