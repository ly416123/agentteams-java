package io.agentteams.controlplane.persistence;

import io.agentteams.controlplane.security.Principal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class DomainEventRepository {

    private final JdbcTemplate jdbc;

    DomainEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(DomainEventRecord event) {
        jdbc.update("""
                INSERT INTO domain_events
                    (id, event_id, aggregate_type, aggregate_id, event_type, payload,
                     occurred_at, aggregate_version, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event.id(), event.eventId(), event.aggregateType(), event.aggregateId(), event.eventType(),
                JdbcSupport.json(event.payloadJson()), JdbcSupport.timestamp(event.occurredAt()),
                event.aggregateVersion(), JdbcSupport.timestamp(event.createdAt()),
                JdbcSupport.timestamp(event.updatedAt()), event.version());
    }

    public Optional<DomainEventRecord> findByEventId(UUID eventId) {
        return jdbc.query("""
                SELECT id, event_id, aggregate_type, aggregate_id, event_type, payload::text,
                       occurred_at, aggregate_version, created_at, updated_at, version
                  FROM domain_events WHERE event_id = ?
                """, this::map, eventId).stream().findFirst();
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM domain_events", Long.class);
        return count == null ? 0 : count;
    }

    public java.util.List<DomainEventRecord> findTaskEvents(Principal principal, UUID taskId, long after, int limit) {
        return jdbc.query("""
                SELECT e.id, e.event_id, e.aggregate_type, e.aggregate_id, e.event_type, e.payload::text,
                       e.occurred_at, e.aggregate_version, e.created_at, e.updated_at, e.version
                 FROM domain_events e
                  JOIN resource_scopes s ON s.resource_type = 'TASK' AND s.resource_id = e.aggregate_id
                 WHERE e.aggregate_type = 'task' AND e.aggregate_id = ? AND e.aggregate_version > ?
                   AND s.tenant_id = ? AND s.project_id = ? AND s.team = ?
                   AND EXISTS (SELECT 1 FROM project_memberships m
                                WHERE m.tenant_id = s.tenant_id AND m.project_id::text = s.project_id
                                  AND m.subject = ? AND m.status = 'ACTIVE')
                 ORDER BY e.aggregate_version ASC, e.id ASC LIMIT ?
                """, this::map, taskId, after, principal.scope().tenant(), principal.scope().project(),
                principal.scope().team(), principal.subject(), limit);
    }

    public java.util.List<String> eventTypes() {
        return jdbc.query("SELECT event_type FROM domain_events ORDER BY event_type",
                (rs, row) -> rs.getString(1));
    }

    private DomainEventRecord map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new DomainEventRecord(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                rs.getString("event_type"), rs.getString("payload"), JdbcSupport.instant(rs, "occurred_at"),
                rs.getLong("aggregate_version"), JdbcSupport.instant(rs, "created_at"),
                JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
