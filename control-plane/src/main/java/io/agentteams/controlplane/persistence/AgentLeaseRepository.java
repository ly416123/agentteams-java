package io.agentteams.controlplane.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class AgentLeaseRepository {

    private final JdbcTemplate jdbc;

    AgentLeaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AgentLeaseRecord lease) {
        jdbc.update("""
                INSERT INTO agent_leases
                    (id, agent_id, task_attempt_id, acquired_at, expires_at, released_at, status,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, lease.id(), lease.agentId(), lease.taskAttemptId(), JdbcSupport.timestamp(lease.acquiredAt()),
                JdbcSupport.timestamp(lease.expiresAt()), nullableTimestamp(lease.releasedAt()), lease.status(),
                JdbcSupport.timestamp(lease.createdAt()), JdbcSupport.timestamp(lease.updatedAt()), lease.version());
    }

    public Optional<AgentLeaseRecord> findById(UUID id) {
        return jdbc.query("""
                SELECT id, agent_id, task_attempt_id, acquired_at, expires_at, released_at, status,
                       created_at, updated_at, version
                  FROM agent_leases WHERE id = ?
                """, this::map, id).stream().findFirst();
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM agent_leases", Long.class);
        return count == null ? 0 : count;
    }

    public long countActiveForAgent(UUID agentId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM agent_leases
                 WHERE agent_id = ? AND status = 'ACTIVE' AND released_at IS NULL
                """, Long.class, agentId);
        return count == null ? 0 : count;
    }

    public AgentLeaseRecord updateStatus(UUID id, String status, Instant releasedAt,
            long expectedVersion, Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE agent_leases
                   SET status = ?, released_at = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, status, nullableTimestamp(releasedAt), JdbcSupport.timestamp(updatedAt), id, expectedVersion);
        if (updated == 0) {
            throw new OptimisticLockFailure("agent_lease", id, expectedVersion, actualVersion(id));
        }
        return findById(id).orElseThrow();
    }

    public AgentLeaseRecord updateExpiry(UUID id, Instant expiresAt, long expectedVersion, Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE agent_leases
                   SET expires_at = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND status = 'ACTIVE' AND released_at IS NULL
                """, JdbcSupport.timestamp(expiresAt), JdbcSupport.timestamp(updatedAt), id, expectedVersion);
        if (updated == 0) {
            throw new OptimisticLockFailure("agent_lease", id, expectedVersion, actualVersion(id));
        }
        return findById(id).orElseThrow();
    }

    private long actualVersion(UUID id) {
        return jdbc.query("SELECT version FROM agent_leases WHERE id = ?", (rs, row) -> rs.getLong(1), id)
                .stream().findFirst().orElse(-1L);
    }

    private static java.sql.Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : JdbcSupport.timestamp(value);
    }

    private AgentLeaseRecord map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        java.sql.Timestamp released = rs.getTimestamp("released_at");
        return new AgentLeaseRecord(rs.getObject("id", UUID.class), rs.getObject("agent_id", UUID.class),
                rs.getObject("task_attempt_id", UUID.class), JdbcSupport.instant(rs, "acquired_at"),
                JdbcSupport.instant(rs, "expires_at"), released == null ? null : released.toInstant(),
                rs.getString("status"), JdbcSupport.instant(rs, "created_at"),
                JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
