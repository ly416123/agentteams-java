package io.agentteams.controlplane.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import io.agentteams.application.api.ExecutionEventPort;
import io.agentteams.application.api.ExecutionEventPort.ExecutionPhase;
import io.agentteams.application.api.ExecutionEventPort.ModelCallUsage;
import io.agentteams.application.api.ExecutionEventPort.TaskExecutionCommand;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class JdbcModelCallAuditRecorderTest {
    @Mock
    private JdbcTemplate jdbc;

    @Test
    void persistsTerminalUsageWithEventIdempotencyAndDimensions() {
        UUID eventId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskExecutionCommand command = new TaskExecutionCommand(eventId, 7, UUID.randomUUID(), UUID.randomUUID(),
                Instant.EPOCH, "worker-1", "gateway", ExecutionPhase.SUCCEEDED, "", "", "corr", "", "",
                new ModelCallUsage("qwen", "qwen-plus", 42, 3, 5, "tenant-a", "project-a", "worker-1",
                        taskId.toString(), "team-a", "create_task", "quota-1", "project"));

        new JdbcModelCallAuditRecorder(jdbc).record(taskId, command);

        ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(eq("""
                INSERT INTO model_call_audits(id, source_event_id, organization_id, actor_subject, provider, model, latency_millis,
                    prompt_tokens, completion_tokens, request_hash, response_hash, outcome, error_category,
                    occurred_at, tenant_id, project_id, cost_usd, cost_status, worker_id, task_id, team_id,
                    tool_id, quota_id, quota_dimension)
                VALUES (?, ?, (SELECT organization_id::text FROM legacy_tenant_mappings WHERE legacy_tenant_key = ?),
                    (SELECT actor FROM tasks WHERE id = ?), ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?,
                    COALESCE((SELECT p.id::text FROM projects p WHERE p.tenant_id = ? AND (p.id::text = ? OR p.name = ?)), ?),
                    0, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_event_id) WHERE source_event_id IS NOT NULL DO NOTHING
                """), values.capture());
        // Every placeholder must be bound: an unbound parameter only surfaces at runtime.
        assertThat(values.getValue()).hasSize(25);
        assertThat(values.getValue()[0]).isEqualTo(eventId);
        assertThat(values.getValue()[1]).isEqualTo(eventId);
        assertThat(values.getValue()[2]).isEqualTo("tenant-a");
        assertThat(values.getValue()[3]).isEqualTo(taskId);
        assertThat(values.getValue()[4]).isEqualTo("qwen");
        assertThat(values.getValue()[13]).isEqualTo("tenant-a");
        // project_id is bound through the canonicalization subquery: tenant filter, id/name match and raw fallback.
        assertThat(values.getValue()[14]).isEqualTo("tenant-a");
        assertThat(values.getValue()[15]).isEqualTo("project-a");
        assertThat(values.getValue()[16]).isEqualTo("project-a");
        assertThat(values.getValue()[17]).isEqualTo("project-a");
        assertThat(values.getValue()[18]).isEqualTo("UNPRICED");
        assertThat(values.getValue()[19]).isEqualTo("worker-1");
        assertThat(values.getValue()[20]).isEqualTo(taskId.toString());
        assertThat(values.getValue()[21]).isEqualTo("team-a");
        assertThat(values.getValue()[23]).isEqualTo("quota-1");
        assertThat(values.getValue()[24]).isEqualTo("project");
    }
}
