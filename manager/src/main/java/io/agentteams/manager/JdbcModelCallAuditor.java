package io.agentteams.manager;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable audit sink; it persists hashes and operational metadata, never prompts or API keys. */
public final class JdbcModelCallAuditor implements ModelCallAuditor {
    private final JdbcTemplate jdbc;

    public JdbcModelCallAuditor(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc, "jdbc"); }

    @Override
    public void record(ModelCallAudit audit) {
        Objects.requireNonNull(audit, "audit");
        jdbc.update("""
                INSERT INTO model_call_audits(id, provider, model, latency_millis, prompt_tokens,
                    completion_tokens, request_hash, response_hash, outcome, error_category, occurred_at,
                    tenant_id, project_id, cost_usd, worker_id, task_id, team_id, tool_id, quota_id, quota_dimension)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), audit.provider(), audit.model(), audit.latency().toMillis(),
                audit.tokenUsage().promptTokens(), audit.tokenUsage().completionTokens(), audit.requestHash(),
                audit.responseHash(), audit.outcome().name(), audit.errorCategory(),
                Timestamp.from(audit.occurredAt()), audit.tenantId(), audit.projectId(), audit.costUsd(),
                audit.dimensions() == null ? null : audit.dimensions().workerId(),
                audit.dimensions() == null ? null : audit.dimensions().taskId(),
                audit.dimensions() == null ? null : audit.dimensions().teamId(),
                audit.dimensions() == null ? null : audit.dimensions().toolId(),
                audit.dimensions() == null ? null : audit.dimensions().quotaId(),
                audit.dimensions() == null ? null : audit.dimensions().quotaDimension());
    }
}
