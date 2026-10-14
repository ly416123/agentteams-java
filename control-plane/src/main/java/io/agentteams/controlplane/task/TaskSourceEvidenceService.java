package io.agentteams.controlplane.task;

import io.agentteams.controlplane.security.ExecutionContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskSourceEvidenceService {
    private final TaskRunObservationRepository runs;
    private final TaskSourceEvidenceRepository evidence;
    public TaskSourceEvidenceService(TaskRunObservationRepository runs, TaskSourceEvidenceRepository evidence,
            java.time.Clock clock) {
        this.runs=Objects.requireNonNull(runs); this.evidence=Objects.requireNonNull(evidence);
        this.clock=Objects.requireNonNull(clock);
    }
    private final java.time.Clock clock;

    @Transactional
    public Receipt capture(UUID taskId, UUID attemptId, UUID leaseId, String callId, String provider,
            String idempotencyKey, List<IncomingSource> incoming) {
        return capture(taskId, attemptId, leaseId, callId, provider, idempotencyKey, incoming, null);
    }

    @Transactional
    public Receipt capture(UUID taskId, UUID attemptId, UUID leaseId, String callId, String provider,
            String idempotencyKey, List<IncomingSource> incoming, ExecutionContext requestingContext) {
        Objects.requireNonNull(taskId); Objects.requireNonNull(attemptId); Objects.requireNonNull(leaseId);
        if (incoming == null || incoming.size() > 20) throw new IllegalArgumentException("source count is invalid");
        final String normalizedCallId = required(callId, 128, "callId"); provider = required(provider, 100, "provider");
        idempotencyKey = required(idempotencyKey, 200, "idempotencyKey");
        ExecutionContext context = runs.contextForTask(taskId).orElseThrow(() -> new IllegalArgumentException("task scope not found"));
        if (requestingContext != null && !context.sameResourceScope(requestingContext)) {
            throw new io.agentteams.controlplane.security.AuthorizationException("task is outside the caller scope");
        }
        UUID runId = evidence.activeRun(context, taskId);
        boolean valid = evidence.isActiveExecution(taskId, attemptId, leaseId, clock.instant());
        if (!valid) throw new IllegalArgumentException("task execution lease is not active");
        Map<String, TaskSourceEvidence.Source> sourcesByUrl = new LinkedHashMap<>();
        incoming.forEach(source -> {
            String title=required(source.title(), 500, "title"); String url=required(source.url(), 2048, "url");
            String hash=required(source.sha256(),64,"sha256").toLowerCase(java.util.Locale.ROOT);
            sourcesByUrl.putIfAbsent(url, new TaskSourceEvidence.Source(title,url,hash,
                    "task-source:"+normalizedCallId+":"+hash, source.snippet()));
        });
        List<TaskSourceEvidence.Source> sources = List.copyOf(sourcesByUrl.values());
        evidence.save(context, taskId, runId, attemptId, normalizedCallId, provider, idempotencyKey, sources);
        return new Receipt(taskId, runId, normalizedCallId, sources.size());
    }

    public TaskSourceEvidence find(ExecutionContext context, UUID taskId, UUID runId) {
        var snapshot = runs.snapshotForRun(context, taskId, runId);
        if (snapshot.isEmpty() || snapshot.get().runStatus() == null) {
            throw new IllegalArgumentException("task run not found");
        }
        return evidence.find(context, taskId, runId, 500, 10_000);
    }
    private static String required(String value,int max,String field) {
        if(value==null || value.isBlank() || value.trim().length()>max) throw new IllegalArgumentException(field+" is invalid");
        return value.trim();
    }
    public record IncomingSource(String title,String url,String sha256,String snippet) {
        public IncomingSource(String title,String url,String sha256) { this(title,url,sha256,""); }
    }
    public record Receipt(UUID taskId,UUID runId,String callId,int sourceCount) { }
}
