package io.agentteams.controlplane.conversation;

import io.agentteams.application.api.TimelineItem;
import io.agentteams.controlplane.persistence.JdbcSupport;
import io.agentteams.controlplane.security.ExecutionContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC projection of task domain and process facts for a conversation timeline. */
@Repository
public final class JdbcConversationTaskEventSource implements ConversationTaskEventSource {
    private static final Comparator<TimelineItem> ORDER = Comparator.comparing(TimelineItem::occurredAt)
            .thenComparingLong(TimelineItem::sequence).thenComparing(TimelineItem::itemId);
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcConversationTaskEventSource(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcConversationTaskEventSource(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<TimelineItem> list(ExecutionContext context, UUID conversationId, Set<String> visibility,
            long after, int limit) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(visibility, "visibility");
        if (after < 0) throw new IllegalArgumentException("after must not be negative");
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        if (visibility.isEmpty()) return List.of();
        List<TimelineItem> result = new ArrayList<>();
        if (visibility.contains("TEAM") || visibility.contains("PUBLIC")) {
            result.addAll(jdbc.query("""
                    SELECT e.event_id, e.aggregate_id, e.event_type, e.occurred_at,
                           e.aggregate_version, t.source_conversation_id, t.actor
                      FROM domain_events e
                      JOIN tasks t ON t.id = e.aggregate_id
                      JOIN resource_scopes s ON s.resource_type = 'TASK' AND s.resource_id = t.id
                     WHERE e.aggregate_type = 'task' AND t.source_conversation_id = ?
                       AND s.tenant_id = ? AND s.project_id = ? AND s.team = ?
                       AND e.aggregate_version > ?
                     ORDER BY e.occurred_at, e.aggregate_version, e.event_id
                     LIMIT ?
                    """, this::mapDomainEvent, conversationId, context.tenantId(), context.projectId(),
                    context.teamId(), after, limit));
        }
        result.addAll(jdbc.query("""
                SELECT e.id, e.task_id, e.sequence, e.event_type, e.visibility, e.occurred_at,
                       e.payload_ref, t.source_conversation_id, t.actor
                  FROM task_process_events e
                  JOIN tasks t ON t.id = e.task_id
                  JOIN resource_scopes s ON s.resource_type = 'TASK' AND s.resource_id = t.id
                 WHERE t.source_conversation_id = ? AND e.organization_id = ? AND e.tenant_id = ?
                   AND s.project_id = ? AND s.team = ? AND e.sequence > ?
                 ORDER BY e.occurred_at, e.sequence, e.id
                 LIMIT ?
                """, this::mapProcessEvent, conversationId, context.organizationId(), context.tenantId(),
                context.projectId(), context.teamId(), after, limit));
        return result.stream().filter(item -> visibility.contains(item.visibility()))
                .sorted(ORDER).limit(limit).toList();
    }

    private TimelineItem mapDomainEvent(ResultSet rs, int row) throws SQLException {
        UUID eventId = rs.getObject("event_id", UUID.class);
        return new TimelineItem(eventId.toString(), "TASK_EVENT", rs.getObject("source_conversation_id", UUID.class),
                rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"), rs.getString("actor"),
                JdbcSupport.instant(rs, "occurred_at"), rs.getLong("aggregate_version"), "TEAM",
                "domain-event:" + eventId, rs.getString("event_type"), rs.getLong("aggregate_version"));
    }

    private TimelineItem mapProcessEvent(ResultSet rs, int row) throws SQLException {
        UUID eventId = rs.getObject("id", UUID.class);
        String visibility = processVisibility(rs.getString("visibility"));
        String payloadRef = rs.getString("payload_ref");
        if (payloadRef == null || payloadRef.isBlank()) payloadRef = "task-process-event:" + eventId;
        return new TimelineItem(eventId.toString(), "PROCESS_EVENT", rs.getObject("source_conversation_id", UUID.class),
                rs.getObject("task_id", UUID.class), rs.getString("event_type"), rs.getString("actor"),
                JdbcSupport.instant(rs, "occurred_at"), rs.getLong("sequence"), visibility,
                payloadRef, rs.getString("event_type"), rs.getLong("sequence"));
    }

    private static String processVisibility(String value) {
        return switch (value) {
            case "REQUESTER" -> "PRIVATE";
            case "PROJECT_MEMBER" -> "TEAM";
            case "TENANT_ADMIN", "SECURITY_AUDITOR", "INTERNAL_ONLY" -> "INTERNAL";
            default -> throw new IllegalArgumentException("unsupported task event visibility: " + value);
        };
    }
}
