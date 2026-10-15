package io.agentteams.controlplane.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcArtifactQualityEventOutboxRepository implements ArtifactQualityEventOutboxRepository {
    private static final Pattern SAFE_ERROR_TYPE = Pattern.compile("[A-Za-z0-9_$]{1,128}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Autowired
    public JdbcArtifactQualityEventOutboxRepository(DataSource source, ObjectMapper mapper) {
        this(new JdbcTemplate(source), mapper);
    }

    public JdbcArtifactQualityEventOutboxRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public boolean enqueue(ArtifactQualityEvent event) {
        Objects.requireNonNull(event, "event");
        try {
            String payload = mapper.writeValueAsString(event);
            return jdbc.update("""
                    INSERT INTO artifact_quality_event_outbox(event_id, payload)
                    VALUES (?, ?::jsonb)
                    ON CONFLICT (event_id) DO NOTHING
                    """, event.id(), payload) == 1;
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("quality event could not be serialized", failure);
        }
    }

    @Override
    public List<PendingEvent> claimDue(Instant now, Duration lease, int limit) {
        Objects.requireNonNull(now, "now");
        if (lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        return jdbc.query("""
                WITH due AS (
                    SELECT event_id
                      FROM artifact_quality_event_outbox
                     WHERE next_attempt_at <= ? AND (lease_until IS NULL OR lease_until <= ?)
                     ORDER BY next_attempt_at, enqueued_at, event_id
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE artifact_quality_event_outbox AS pending
                   SET lease_until = ?, attempt_count = pending.attempt_count + 1
                  FROM due
                 WHERE pending.event_id = due.event_id
                RETURNING pending.event_id, pending.payload::text, pending.attempt_count
                """, (rs, row) -> {
                    try {
                        ArtifactQualityEvent event = mapper.readValue(rs.getString("payload"), ArtifactQualityEvent.class);
                        if (!rs.getObject("event_id", UUID.class).equals(event.id())) {
                            throw new IllegalStateException("quality outbox payload identity mismatch");
                        }
                        return new PendingEvent(event, rs.getInt("attempt_count"));
                    } catch (JsonProcessingException failure) {
                        throw new IllegalStateException("quality outbox payload could not be decoded", failure);
                    }
                }, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), limit,
                java.sql.Timestamp.from(now.plus(lease)));
    }

    @Override
    public void markDelivered(UUID eventId) {
        jdbc.update("DELETE FROM artifact_quality_event_outbox WHERE event_id = ?", eventId);
    }

    @Override
    public void retry(UUID eventId, Instant nextAttemptAt, String failureType) {
        String safeType = failureType != null && SAFE_ERROR_TYPE.matcher(failureType).matches()
                ? failureType : "UnknownFailure";
        jdbc.update("""
                UPDATE artifact_quality_event_outbox
                   SET lease_until = NULL, next_attempt_at = ?, last_error_type = ?
                 WHERE event_id = ?
                """, java.sql.Timestamp.from(nextAttemptAt), safeType, eventId);
    }

    @Override
    public int pendingCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM artifact_quality_event_outbox", Integer.class);
        return count == null ? 0 : count;
    }
}
