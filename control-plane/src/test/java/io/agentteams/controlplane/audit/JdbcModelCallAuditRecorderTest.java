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
                INSERT INTO model_call_audits(id, source_event_id, provider, model, latency_millis,
                    prompt_tokens, completion_tokens, request_hash, response_hash, outcome, error_category,
                    occurred_at, tenant_id, project_id, cost_usd, worker_id, task_id, team_id, tool_id,
                    quota_id, quota_dimension)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_event_id) DO NOTHING
                """), values.capture());
        assertThat(values.getValue()[0]).isEqualTo(eventId);
        assertThat(values.getValue()[1]).isEqualTo(eventId);
        assertThat(values.getValue()[2]).isEqualTo("qwen");
        assertThat(values.getValue()[11]).isEqualTo("tenant-a");
        assertThat(values.getValue()[12]).isEqualTo("project-a");
        assertThat(values.getValue()[17]).isEqualTo("quota-1");
    }
}
