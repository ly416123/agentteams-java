package io.agentteams.controlplane.token;

import io.agentteams.controlplane.persistence.JdbcSupport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL implementation of the append-only token ledger boundary. */
@Repository
public final class JdbcTokenLedgerRepository implements TokenLedgerRepository {
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcTokenLedgerRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    JdbcTokenLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public TokenReservation reserve(TokenReservation reservation, TokenLedgerEntry entry, String requestHash) {
        Optional<TokenReservation> existing = findByReserveKey(reservation.scope(), reservation.reserveIdempotencyKey());
        if (existing.isPresent()) {
            if (!requestHash.equals(reserveHash(existing.get().id()))) {
                throw new TokenLedgerConflictException("idempotency key is already bound to a different request");
            }
            return existing.get();
        }
        try {
            jdbc.update("""
                    INSERT INTO token_ledger_reservations
                        (id, organization_id, tenant_id, project_id, task_id, run_id, estimated_tokens,
                         settled_tokens, state, reserve_idempotency_key, reserve_request_hash, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'RESERVED', ?, ?, ?, ?)
                    """, reservation.id(), reservation.scope().organizationId(), reservation.scope().tenantId(),
                    reservation.scope().projectId(), reservation.taskId(), reservation.runId(), reservation.estimatedTokens(),
                    reservation.reserveIdempotencyKey(), requestHash, timestamp(reservation.createdAt()),
                    timestamp(reservation.updatedAt()));
            insertEntry(entry);
            return reservation;
        } catch (DuplicateKeyException duplicate) {
            TokenReservation winner = findByReserveKey(reservation.scope(), reservation.reserveIdempotencyKey())
                    .orElseThrow(() -> duplicate);
            if (!requestHash.equals(reserveHash(winner.id()))) {
                throw new TokenLedgerConflictException("idempotency key is already bound to a different request");
            }
            return winner;
        }
    }

    @Override
    public TokenReservation transition(TokenLedgerScope scope, UUID id, TokenReservation.State expected,
            TokenReservation.State next, long settledTokens, String operationKey, String requestHash,
            TokenLedgerEntry entry, Instant now) {
        TokenReservation current = selectForUpdate(scope, id).orElseThrow(TokenLedgerNotFoundException::new);
        String currentKey = next == TokenReservation.State.SETTLED
                ? current.settleIdempotencyKey() : current.releaseIdempotencyKey();
        String currentHash = next == TokenReservation.State.SETTLED
                ? hashForOperation(current.id(), TokenLedgerEntry.Kind.SETTLED) : hashForOperation(current.id(), TokenLedgerEntry.Kind.RELEASED);
        if (currentKey != null) {
            if (currentKey.equals(operationKey) && requestHash.equals(currentHash)) return current;
            throw new TokenLedgerConflictException("reservation is terminal or operation idempotency key conflicts");
        }
        if (current.state() != expected) throw new TokenLedgerConflictException("reservation is terminal");
        jdbc.update("""
                UPDATE token_ledger_reservations
                   SET state = ?, settled_tokens = ?, settle_idempotency_key = ?, settle_request_hash = ?,
                       release_idempotency_key = ?, release_request_hash = ?, updated_at = ?
                 WHERE id = ? AND organization_id = ? AND tenant_id = ?
                """, next.name(), settledTokens,
                next == TokenReservation.State.SETTLED ? operationKey : null,
                next == TokenReservation.State.SETTLED ? requestHash : null,
                next == TokenReservation.State.RELEASED ? operationKey : null,
                next == TokenReservation.State.RELEASED ? requestHash : null,
                timestamp(now), id, scope.organizationId(), scope.tenantId());
        insertEntry(entry);
        return selectForUpdate(scope, id).orElseThrow(TokenLedgerNotFoundException::new);
    }

    @Override
    public Optional<TokenReservation> find(TokenLedgerScope scope, UUID id) {
        return jdbc.query(selectReservation() + " WHERE id = ? AND organization_id = ? AND tenant_id = ?",
                this::mapReservation, id, scope.organizationId(), scope.tenantId()).stream().findFirst();
    }

    @Override
    public List<TokenLedgerEntry> entries(TokenLedgerScope scope, UUID reservationId) {
        return jdbc.query(selectEntry() + " WHERE reservation_id = ? AND organization_id = ? AND tenant_id = ?"
                        + " ORDER BY occurred_at, id", this::mapEntry, reservationId, scope.organizationId(), scope.tenantId());
    }

    private Optional<TokenReservation> findByReserveKey(TokenLedgerScope scope, String key) {
        return jdbc.query(selectReservation() + " WHERE organization_id = ? AND tenant_id = ?"
                        + " AND project_id IS NOT DISTINCT FROM ? AND reserve_idempotency_key = ? FOR UPDATE",
                this::mapReservation, scope.organizationId(), scope.tenantId(), scope.projectId(), key).stream().findFirst();
    }

    private Optional<TokenReservation> selectForUpdate(TokenLedgerScope scope, UUID id) {
        return jdbc.query(selectReservation() + " WHERE id = ? AND organization_id = ? AND tenant_id = ? FOR UPDATE",
                this::mapReservation, id, scope.organizationId(), scope.tenantId()).stream().findFirst();
    }

    private String reserveHash(UUID id) {
        return jdbc.queryForObject("SELECT reserve_request_hash FROM token_ledger_reservations WHERE id = ?", String.class, id);
    }

    private String hashForOperation(UUID id, TokenLedgerEntry.Kind kind) {
        String column = kind == TokenLedgerEntry.Kind.SETTLED ? "settle_request_hash" : "release_request_hash";
        return jdbc.queryForObject("SELECT " + column + " FROM token_ledger_reservations WHERE id = ?", String.class, id);
    }

    private void insertEntry(TokenLedgerEntry entry) {
        jdbc.update("""
                INSERT INTO token_ledger_entries
                    (id, reservation_id, organization_id, tenant_id, project_id, task_id, run_id, kind,
                     tokens, operation_key, source, model, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, entry.id(), entry.reservationId(), entry.scope().organizationId(), entry.scope().tenantId(),
                entry.scope().projectId(), entry.taskId(), entry.runId(), entry.kind().name(), entry.tokens(),
                entry.operationKey(), entry.source(), entry.model(), timestamp(entry.occurredAt()));
    }

    private TokenReservation mapReservation(ResultSet rs, int row) throws SQLException {
        TokenLedgerScope scope = new TokenLedgerScope(rs.getString("organization_id"), rs.getString("tenant_id"),
                rs.getString("project_id"));
        return new TokenReservation(rs.getObject("id", UUID.class), scope, rs.getObject("task_id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getLong("estimated_tokens"), rs.getLong("settled_tokens"),
                TokenReservation.State.valueOf(rs.getString("state")), rs.getString("reserve_idempotency_key"),
                rs.getString("settle_idempotency_key"), rs.getString("release_idempotency_key"),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"));
    }

    private TokenLedgerEntry mapEntry(ResultSet rs, int row) throws SQLException {
        return new TokenLedgerEntry(rs.getObject("id", UUID.class), rs.getObject("reservation_id", UUID.class),
                new TokenLedgerScope(rs.getString("organization_id"), rs.getString("tenant_id"), rs.getString("project_id")),
                rs.getObject("task_id", UUID.class), rs.getObject("run_id", UUID.class),
                TokenLedgerEntry.Kind.valueOf(rs.getString("kind")), rs.getLong("tokens"),
                rs.getString("operation_key"), rs.getString("source"), rs.getString("model"),
                JdbcSupport.instant(rs, "occurred_at"));
    }

    private static String selectReservation() {
        return "SELECT id, organization_id, tenant_id, project_id, task_id, run_id, estimated_tokens, settled_tokens, state, "
                + "reserve_idempotency_key, settle_idempotency_key, release_idempotency_key, created_at, updated_at "
                + "FROM token_ledger_reservations";
    }

    private static String selectEntry() {
        return "SELECT id, reservation_id, organization_id, tenant_id, project_id, task_id, run_id, kind, tokens, "
                + "operation_key, source, model, occurred_at FROM token_ledger_entries";
    }

    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
}
