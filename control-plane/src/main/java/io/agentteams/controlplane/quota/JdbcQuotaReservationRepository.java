package io.agentteams.controlplane.quota;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable claim and idempotency store for gateway quota reservations. */
@Repository
public class JdbcQuotaReservationRepository {
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcQuotaReservationRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    JdbcQuotaReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    Optional<ReservationRecord> findByAcquire(String tenantId, String projectId, String key) {
        return jdbc.query("""
                SELECT reservation_id, tenant_id, project_id, estimated_tokens, state
                  FROM quota_reservations
                 WHERE tenant_id = ? AND project_id = ? AND acquire_idempotency_key = ?
                 FOR UPDATE
                """, (rs, row) -> new ReservationRecord(rs.getObject("reservation_id", UUID.class),
                        rs.getString("tenant_id"), rs.getString("project_id"),
                        rs.getLong("estimated_tokens"), rs.getString("state")),
                tenantId, projectId, key).stream().findFirst();
    }

    void insertPending(UUID reservationId, String tenantId, String projectId, String key,
            long estimatedTokens, Instant now) {
        jdbc.update("""
                INSERT INTO quota_reservations
                    (reservation_id, tenant_id, project_id, acquire_idempotency_key,
                     estimated_tokens, state, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, reservationId, tenantId, projectId, key, estimatedTokens,
                Timestamp.from(now), Timestamp.from(now));
    }

    void markAcquired(UUID reservationId, Instant now) {
        jdbc.update("""
                UPDATE quota_reservations SET state = 'ACQUIRED', updated_at = ?
                 WHERE reservation_id = ?
                """, Timestamp.from(now), reservationId);
    }

    void markReleased(UUID reservationId, Instant now) {
        jdbc.update("""
                UPDATE quota_reservations SET state = 'RELEASED', updated_at = ?
                 WHERE reservation_id = ?
                """, Timestamp.from(now), reservationId);
    }

    void delete(UUID reservationId) {
        jdbc.update("DELETE FROM quota_reservations WHERE reservation_id = ?", reservationId);
    }

    Optional<ReservationRecord> findById(UUID reservationId, String tenantId, String projectId) {
        return jdbc.query("""
                SELECT reservation_id, tenant_id, project_id, estimated_tokens, state
                  FROM quota_reservations
                 WHERE reservation_id = ? AND tenant_id = ? AND project_id = ?
                 FOR UPDATE
                """, (rs, row) -> new ReservationRecord(rs.getObject("reservation_id", UUID.class),
                        rs.getString("tenant_id"), rs.getString("project_id"),
                        rs.getLong("estimated_tokens"), rs.getString("state")),
                reservationId, tenantId, projectId).stream().findFirst();
    }

    Optional<ReleaseRecord> findRelease(String tenantId, String projectId, String key) {
        return jdbc.query("""
                SELECT reservation_id, accepted, protocol_error
                  FROM quota_reservation_releases
                 WHERE tenant_id = ? AND project_id = ? AND idempotency_key = ?
                """, (rs, row) -> new ReleaseRecord(rs.getObject("reservation_id", UUID.class),
                        rs.getBoolean("accepted"), rs.getString("protocol_error")),
                tenantId, projectId, key).stream().findFirst();
    }

    void insertRelease(String tenantId, String projectId, UUID reservationId, String key,
            boolean accepted, String protocolError, Instant now) {
        jdbc.update("""
                INSERT INTO quota_reservation_releases
                    (tenant_id, project_id, reservation_id, idempotency_key,
                     accepted, protocol_error, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, tenantId, projectId, reservationId, key, accepted, protocolError,
                Timestamp.from(now));
    }

    record ReservationRecord(UUID id, String tenantId, String projectId,
            long estimatedTokens, String state) { }

    record ReleaseRecord(UUID reservationId, boolean accepted, String protocolError) { }
}
