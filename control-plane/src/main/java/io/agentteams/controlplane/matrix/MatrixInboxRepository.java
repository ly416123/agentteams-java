package io.agentteams.controlplane.matrix;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class MatrixInboxRepository {
    private final JdbcTemplate jdbc;

    public MatrixInboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean claimTransaction(String transactionId, Instant receivedAt) {
        int inserted = jdbc.update("""
                INSERT INTO matrix_inbox_transactions(id, transaction_id, received_at)
                VALUES (?, ?, ?)
                ON CONFLICT (transaction_id) DO NOTHING
                """, UUID.randomUUID(), transactionId, java.sql.Timestamp.from(receivedAt));
        if (inserted == 1) return true;
        Boolean pending = jdbc.query("""
                SELECT processed_at IS NULL FROM matrix_inbox_transactions WHERE transaction_id = ?
                """, (rs, row) -> rs.getBoolean(1), transactionId).stream().findFirst().orElse(false);
        return pending;
    }

    public void completeTransaction(String transactionId, Instant processedAt) {
        jdbc.update("""
                UPDATE matrix_inbox_transactions SET processed_at = ?
                 WHERE transaction_id = ? AND processed_at IS NULL
                """, java.sql.Timestamp.from(processedAt), transactionId);
    }

    public boolean claim(String transactionId, String eventId, String roomId, String sender, String body, Instant receivedAt) {
        int inserted = jdbc.update("""
                INSERT INTO matrix_inbox_events(id, transaction_id, event_id, room_id, sender, body, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, UUID.randomUUID(), transactionId, eventId, roomId, sender, body,
                java.sql.Timestamp.from(receivedAt));
        return inserted == 1;
    }
}
