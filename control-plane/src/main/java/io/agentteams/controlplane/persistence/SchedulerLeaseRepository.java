package io.agentteams.controlplane.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Database-backed scheduler lease; the scheduler remains restart-safe and single-active. */
public final class SchedulerLeaseRepository {
    private final JdbcTemplate jdbc;

    public SchedulerLeaseRepository(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc, "jdbc"); }

    public boolean tryAcquire(String name, String owner, Instant now, Duration leaseDuration) {
        requireText(name, "name");
        requireText(owner, "owner");
        Objects.requireNonNull(now, "now");
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        Instant expires = now.plus(leaseDuration);
        return jdbc.update("""
                INSERT INTO scheduler_leases(name, owner, expires_at, updated_at, version)
                VALUES (?, ?, ?, ?, 0)
                ON CONFLICT (name) DO UPDATE SET owner = EXCLUDED.owner,
                    expires_at = EXCLUDED.expires_at, updated_at = EXCLUDED.updated_at,
                    version = scheduler_leases.version + 1
                  WHERE scheduler_leases.expires_at <= ? OR scheduler_leases.owner = ?
                """, name, owner, JdbcSupport.timestamp(expires), JdbcSupport.timestamp(now),
                JdbcSupport.timestamp(now), owner) == 1;
    }

    public boolean release(String name, String owner, Instant now) {
        return jdbc.update("""
                UPDATE scheduler_leases SET expires_at = ?, updated_at = ?, version = version + 1
                 WHERE name = ? AND owner = ?
                """, JdbcSupport.timestamp(now), JdbcSupport.timestamp(now), name, owner) == 1;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
