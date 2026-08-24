package io.agentteams.controlplane.audit;

import io.agentteams.application.api.ExecutionEventPort.ModelCallUsage;
import io.agentteams.application.api.ExecutionEventPort.TaskExecutionCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persists Worker runtime usage with event-id idempotency and no prompt content. */
public final class JdbcModelCallAuditRecorder implements ModelCallAuditRecorder {
    private final JdbcTemplate jdbc;

    public JdbcModelCallAuditRecorder(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void record(UUID taskId, TaskExecutionCommand command) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(command, "command");
        ModelCallUsage usage = Objects.requireNonNull(command.modelCallUsage(), "modelCallUsage");
        jdbc.update("""
                INSERT INTO model_call_audits(id, source_event_id, provider, model, latency_millis,
                    prompt_tokens, completion_tokens, request_hash, response_hash, outcome, error_category,
                    occurred_at, tenant_id, project_id, cost_usd, worker_id, task_id, team_id, tool_id,
                    quota_id, quota_dimension)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_event_id) DO NOTHING
                """, command.eventId(), command.eventId(), usage.provider(), usage.model(), usage.latencyMillis(),
                usage.promptTokens(), usage.completionTokens(), requestHash(taskId, command),
                command.phase() == io.agentteams.application.api.ExecutionEventPort.ExecutionPhase.SUCCEEDED
                        ? "SUCCESS" : "FAILURE",
                command.phase() == io.agentteams.application.api.ExecutionEventPort.ExecutionPhase.SUCCEEDED
                        ? null : command.failureCode(), Timestamp.from(command.occurredAt()), usage.tenantId(),
                usage.projectId(), usage.workerId(), usage.taskId(), usage.teamId(), usage.toolId(), usage.quotaId(),
                usage.quotaDimension());
    }

    private static String requestHash(UUID taskId, TaskExecutionCommand command) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("runtime\n" + taskId + "\n" + command.attemptId())
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
