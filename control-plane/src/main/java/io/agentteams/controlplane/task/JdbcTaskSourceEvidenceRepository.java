package io.agentteams.controlplane.task;

import io.agentteams.controlplane.persistence.JdbcSupport;
import io.agentteams.controlplane.security.ExecutionContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;

@Repository
public class JdbcTaskSourceEvidenceRepository implements TaskSourceEvidenceRepository {
    private final JdbcTemplate jdbc;
    @Autowired
    public JdbcTaskSourceEvidenceRepository(DataSource source) { this(new JdbcTemplate(source)); }
    JdbcTaskSourceEvidenceRepository(JdbcTemplate jdbc) { this.jdbc = java.util.Objects.requireNonNull(jdbc); }

    @Override public UUID activeRun(ExecutionContext context, UUID taskId) {
        List<UUID> active = jdbc.query("SELECT id FROM task_runs WHERE task_id=? AND organization_id=? AND tenant_id=? AND status='RUNNING' ORDER BY started_at DESC NULLS LAST LIMIT 2",
                (rs, row) -> rs.getObject(1, UUID.class), taskId, context.organizationId(), context.tenantId());
        if (active.size() != 1) throw new IllegalArgumentException("task does not have exactly one active run");
        return active.get(0);
    }

    @Override public boolean isActiveExecution(UUID taskId, UUID attemptId, UUID leaseId, Instant at) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM task_attempts a JOIN agent_leases l ON l.id=a.lease_id
                  JOIN task_assignments x ON x.attempt_id=a.id
                 WHERE a.id=? AND a.task_id=? AND a.lease_id=? AND a.phase='RUNNING'
                   AND a.lease_expires_at>? AND l.status='ACTIVE' AND l.released_at IS NULL AND l.expires_at>?
                   AND x.released_at IS NULL)
                """, Boolean.class, attemptId, taskId, leaseId, JdbcSupport.timestamp(at), JdbcSupport.timestamp(at)));
    }

    @Override public void save(ExecutionContext context, UUID taskId, UUID runId, UUID attemptId, String callId,
            String provider, String idempotencyKey, List<TaskSourceEvidence.Source> sources) {
        List<String> existing = jdbc.query("SELECT task_id::text || ':' || run_id::text || ':' || attempt_id::text || ':' || call_id FROM task_source_evidence_calls WHERE idempotency_key=?",
                (rs, row) -> rs.getString(1), idempotencyKey);
        if (!existing.isEmpty()) {
            String expected = taskId + ":" + runId + ":" + attemptId + ":" + callId;
            if (!expected.equals(existing.get(0))) throw new IllegalArgumentException("idempotency key conflicts with existing evidence");
            return;
        }
        UUID call = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO task_source_evidence_calls
                (id,task_id,run_id,organization_id,tenant_id,attempt_id,call_id,provider,source_count,idempotency_key,captured_at)
                SELECT ?,?,?,?,?,?,?,?,?,?,? WHERE EXISTS (
                  SELECT 1 FROM task_runs WHERE id=? AND task_id=? AND organization_id=? AND tenant_id=? AND status='RUNNING')
                ON CONFLICT DO NOTHING
                """, call, taskId, runId, context.organizationId(), context.tenantId(), attemptId, callId,
                provider, sources.size(), idempotencyKey, JdbcSupport.timestamp(Instant.now()), runId, taskId,
                context.organizationId(), context.tenantId());
        if (inserted == 0) {
            List<String> persisted = jdbc.query("SELECT task_id::text || ':' || run_id::text || ':' || attempt_id::text || ':' || call_id FROM task_source_evidence_calls WHERE idempotency_key=?",
                    (rs, row) -> rs.getString(1), idempotencyKey);
            String expected = taskId + ":" + runId + ":" + attemptId + ":" + callId;
            if (persisted.contains(expected)) return;
            throw new IllegalStateException("source evidence call was not persisted");
        }
        for (int index = 0; index < sources.size(); index++) {
            TaskSourceEvidence.Source source = sources.get(index);
            jdbc.update("INSERT INTO task_source_evidence_items (id,evidence_call_id,source_index,title,canonical_url,source_sha256,snippet,created_at) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (evidence_call_id,source_sha256) DO NOTHING",
                    UUID.randomUUID(), call, index, source.title(), source.url(), source.sha256(), source.snippet(),
                    JdbcSupport.timestamp(Instant.now()));
        }
    }

    @Override public TaskSourceEvidence find(ExecutionContext context, UUID taskId, UUID runId, int callLimit, int sourceLimit) {
        var rows = jdbc.query("""
                SELECT c.id,c.task_id,c.run_id,c.attempt_id,c.call_id,c.provider,c.source_count,c.captured_at,
                       i.title,i.canonical_url,i.source_sha256,i.snippet
                  FROM task_source_evidence_calls c
                  LEFT JOIN task_source_evidence_items i ON i.evidence_call_id=c.id
                 WHERE c.organization_id=? AND c.tenant_id=? AND c.task_id=? AND c.run_id=?
                 ORDER BY c.captured_at,c.id,i.source_index LIMIT ?
                """, (rs, row) -> new EvidenceRow(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getObject("attempt_id", UUID.class),
                rs.getString("call_id"), rs.getString("provider"), rs.getInt("source_count"),
                rs.getTimestamp("captured_at").toInstant(), rs.getString("title"), rs.getString("canonical_url"),
                rs.getString("source_sha256"), rs.getString("snippet")), context.organizationId(), context.tenantId(), taskId, runId,
                Math.max(callLimit * 21, sourceLimit + callLimit));
        Map<UUID, MutableCall> calls = new LinkedHashMap<>(); int count = 0; int total = 0;
        for (EvidenceRow row : rows) {
            MutableCall call = calls.computeIfAbsent(row.id, ignored -> new MutableCall(row));
            if (row.title != null && total < sourceLimit) {
                String ref = "task-source:" + row.callId + ":" + row.hash;
                call.sources.add(new TaskSourceEvidence.Source(row.title, row.url, row.hash, ref, row.snippet)); total++;
            }
        }
        List<TaskSourceEvidence.SearchCall> result = new ArrayList<>();
        for (MutableCall call : calls.values()) {
            result.add(new TaskSourceEvidence.SearchCall(call.row.taskId, call.row.runId, call.row.attemptId,
                    call.row.callId, call.row.provider,
                    call.row.expectedSources == call.sources.size(), call.sources, call.row.at));
        }
        return new TaskSourceEvidence(taskId, runId, result, rows.size() >= Math.max(callLimit * 21, sourceLimit + callLimit),
                result.size(), total);
    }
    private record EvidenceRow(UUID id, UUID taskId, UUID runId, UUID attemptId, String callId, String provider, int expectedSources,
            Instant at, String title, String url, String hash, String snippet) { }
    private static final class MutableCall { final EvidenceRow row; final List<TaskSourceEvidence.Source> sources = new ArrayList<>(); MutableCall(EvidenceRow row) { this.row=row; } }
}
