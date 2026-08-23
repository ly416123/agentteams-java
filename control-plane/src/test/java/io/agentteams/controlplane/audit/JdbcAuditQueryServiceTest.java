package io.agentteams.controlplane.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class JdbcAuditQueryServiceTest {
    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private ResultSet resultSet;

    @Test
    @SuppressWarnings("unchecked")
    void queriesWithFiltersAndRedactsAttributesOnRead() throws Exception {
        UUID id = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-23T00:00:00Z");
        when(resultSet.getObject("id", UUID.class)).thenReturn(id);
        when(resultSet.getString("actor")).thenReturn("operator");
        when(resultSet.getString("action")).thenReturn("worker.update");
        when(resultSet.getString("resource_type")).thenReturn("worker");
        when(resultSet.getString("resource_id")).thenReturn("worker-1");
        when(resultSet.getString("attributes")).thenReturn("{\"token\":\"secret\",\"model\":\"qwen\"}");
        when(resultSet.getObject("occurred_at", Instant.class)).thenReturn(occurredAt);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<AuditEvent> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        });

        var result = new JdbcAuditQueryService(jdbc, new ObjectMapper()).find(
                new JdbcAuditQueryService.AuditEventQuery("operator", "worker.update", "worker", "worker-1",
                        occurredAt.plusSeconds(1), 10));

        assertThat(result).singleElement().satisfies(event -> {
            assertThat(event.id()).isEqualTo(id);
            assertThat(event.attributes()).containsEntry("token", "[REDACTED]")
                    .containsEntry("model", "qwen");
        });
        verify(jdbc).query(eq("SELECT id, actor, action, resource_type, resource_id, attributes, occurred_at "
                + "FROM operation_audit_events WHERE 1 = 1 AND actor = ? AND action = ? AND resource_type = ? "
                + "AND resource_id = ? AND occurred_at < ? ORDER BY occurred_at DESC, id DESC LIMIT ?"),
                any(RowMapper.class), any(Object[].class));
    }
}
