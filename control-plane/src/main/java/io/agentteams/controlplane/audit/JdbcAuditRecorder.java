package io.agentteams.controlplane.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists redacted operation events without coupling audit storage to the foundation transaction. */
@Repository
public final class JdbcAuditRecorder implements AuditRecorder {
    static final String INSERT_SQL = "INSERT INTO operation_audit_events "
            + "(id, actor, action, resource_type, resource_id, attributes, occurred_at) "
            + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditRecorder(DataSource dataSource, ObjectMapper objectMapper) {
        this(new JdbcTemplate(dataSource), objectMapper);
    }

    JdbcAuditRecorder(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void record(AuditEvent event) {
        java.util.Objects.requireNonNull(event, "event");
        String attributesJson;
        try {
            attributesJson = objectMapper.writeValueAsString(RedactingAuditRecorder.redactAttributes(event.attributes()));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("audit attributes could not be serialized", error);
        }
        jdbc.update(INSERT_SQL, event.id(), event.actor(), event.action(), event.resourceType(), event.resourceId(),
                attributesJson, event.occurredAt());
    }

    void record(String actor, String action, String resourceType, String resourceId,
            java.util.Map<String, String> attributes, Instant occurredAt) {
        record(new AuditEvent(java.util.UUID.randomUUID(), actor, action, resourceType, resourceId, attributes,
                occurredAt));
    }
}
