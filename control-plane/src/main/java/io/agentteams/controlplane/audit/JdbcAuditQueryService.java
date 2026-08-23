package io.agentteams.controlplane.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only, bounded audit event query service. */
@Service
public final class JdbcAuditQueryService {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final TypeReference<Map<String, String>> ATTRIBUTES_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditQueryService(DataSource dataSource, ObjectMapper objectMapper) {
        this(new JdbcTemplate(dataSource), objectMapper);
    }

    JdbcAuditQueryService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public List<AuditEvent> find(AuditEventQuery query) {
        java.util.Objects.requireNonNull(query, "query");
        List<Object> arguments = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT id, actor, action, resource_type, resource_id, "
                + "attributes, occurred_at FROM operation_audit_events WHERE 1 = 1");
        appendEquals(sql, arguments, "actor", query.actor());
        appendEquals(sql, arguments, "action", query.action());
        appendEquals(sql, arguments, "resource_type", query.resourceType());
        appendEquals(sql, arguments, "resource_id", query.resourceId());
        if (query.before() != null) {
            sql.append(" AND occurred_at < ?");
            arguments.add(query.before());
        }
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ?");
        arguments.add(query.effectiveLimit());
        return jdbc.query(sql.toString(), this::mapRow, arguments.toArray());
    }

    private static void appendEquals(StringBuilder sql, List<Object> arguments, String column, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND ").append(column).append(" = ?");
            arguments.add(value.trim());
        }
    }

    private AuditEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        try {
            Map<String, String> attributes = objectMapper.readValue(rs.getString("attributes"), ATTRIBUTES_TYPE);
            return new AuditEvent(rs.getObject("id", java.util.UUID.class), rs.getString("actor"),
                    rs.getString("action"), rs.getString("resource_type"), rs.getString("resource_id"),
                    RedactingAuditRecorder.redactAttributes(attributes), rs.getObject("occurred_at", Instant.class));
        } catch (JsonProcessingException error) {
            throw new SQLException("stored audit attributes are not valid JSON", error);
        }
    }

    public record AuditEventQuery(String actor, String action, String resourceType, String resourceId,
            Instant before, Integer limit) {
        public AuditEventQuery {
            if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
                throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
            }
        }

        int effectiveLimit() {
            return limit == null ? DEFAULT_LIMIT : limit;
        }
    }
}
