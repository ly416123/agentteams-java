package io.agentteams.controlplane.quality;

import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.evaluation.EvaluationResultRecord;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.taskfile.TaskFileRecord;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/** Records quality evidence as best-effort immutable events without changing task/evaluation outcomes. */
@Service
public final class ArtifactQualityLedgerService {
    private static final Logger LOG = Logger.getLogger(ArtifactQualityLedgerService.class.getName());
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(bearer\\s+[a-z0-9._~+/=-]+|(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)\\s*[:=])");
    private final ArtifactQualityEventRepository repository;
    private final MeterRegistry meters;

    @Autowired
    public ArtifactQualityLedgerService(ArtifactQualityEventRepository repository, ObjectProvider<MeterRegistry> registries) {
        this(repository, registries.getIfAvailable(SimpleMeterRegistry::new));
    }

    public ArtifactQualityLedgerService(ArtifactQualityEventRepository repository) {
        this(repository, new SimpleMeterRegistry());
    }

    public ArtifactQualityLedgerService(ArtifactQualityEventRepository repository, MeterRegistry meters) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    public void recordGenerationStarted(ExecutionContext context, UUID taskId, UUID runId, UUID eventId,
            long sequence, Instant occurredAt) {
        UUID projectId = UUID.fromString(context.projectId());
        String key = "live:task-start:" + runId + ":" + eventId;
        append(context, event(context, projectId, taskId, runId, sequence, "GENERATE_STARTED", null, null,
                "generation", null, null, "RUNNING", null, null, "Artifact generation started",
                "runtime:task-process:" + eventId, key, occurredAt));
    }

    /** Records an independently uploaded OUTPUT file, including artifacts outside the result manifest. */
    public void recordUploadedOutput(ExecutionContext context, UUID taskId, UUID runId,
            TaskFileRecord file, long sequence, Instant occurredAt) {
        Objects.requireNonNull(file, "file");
        if (!TaskFileRecord.OUTPUT.equals(file.role()) || !TaskFileRecord.AVAILABLE.equals(file.status())) {
            throw new IllegalArgumentException("only available OUTPUT files can be recorded as generated artifacts");
        }
        UUID projectId = UUID.fromString(context.projectId());
        String key = "live:task-file:" + file.id();
        append(context, event(context, projectId, taskId, runId, sequence, "GENERATE_SUCCEEDED", file.name(),
                file.id(), file.sha256(), null,
                "generation", null, null, "SUCCEEDED", null, null, "Artifact file upload completed",
                "runtime:task-file:" + file.id(), key, occurredAt));
    }

    public void recordManifest(ExecutionContext context, TaskResultManifest manifest, Instant occurredAt) {
        UUID projectId = UUID.fromString(context.projectId());
        boolean succeeded = "SUCCEEDED".equalsIgnoreCase(manifest.status());
        boolean failed = "FAILED".equalsIgnoreCase(manifest.status());
        String eventType = succeeded ? "GENERATE_SUCCEEDED" : failed ? "GENERATE_FAILED" : "FINAL_STATUS";
        String status = manifest.status().toUpperCase(Locale.ROOT);
        List<TaskResultManifest.ArtifactMetadata> artifacts = manifest.artifacts();
        int count = succeeded && !artifacts.isEmpty() ? artifacts.size() : 1;
        for (int index = 0; index < count; index++) {
            TaskResultManifest.ArtifactMetadata artifact = succeeded && !artifacts.isEmpty() ? artifacts.get(index) : null;
            String identity = artifact == null ? "no-file" : artifact.sourceFileId() == null
                    ? artifact.name() + ":" + artifact.sha256() : artifact.sourceFileId().toString();
            String key = "live:manifest:" + manifest.runId() + ":" + index + ":" + identity;
            String summary = failed ? "Artifact generation failed; detailed cause is unavailable"
                    : succeeded ? "Artifact generation completed" : "Task execution ended without quality evidence";
            append(context, event(context, projectId, manifest.taskId(), manifest.runId(), index, eventType,
                    artifact, "result-manifest:" + manifest.runId(), failed ? "generation_failed" : null,
                    null, null, status, null, null, summary, "runtime:task-result:" + manifest.runId(), key, occurredAt));
        }
    }

    public void recordEvaluation(ExecutionContext context, UUID taskId, UUID runId, UUID evaluationRunId,
            List<EvaluationResultRecord> results, Instant occurredAt) {
        recordEvaluation(context, taskId, runId, evaluationRunId, results, null, occurredAt);
    }

    public void recordEvaluation(ExecutionContext context, UUID taskId, UUID runId, UUID evaluationRunId,
            List<EvaluationResultRecord> results, TaskResultManifest manifest, Instant occurredAt) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(evaluationRunId, "evaluationRunId");
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(occurredAt, "occurredAt");
        UUID projectId = UUID.fromString(context.projectId());
        List<EvaluationResultRecord> quality = results.stream().filter(ArtifactQualityLedgerService::qualityMetric).toList();
        int sequence = 0;
        for (EvaluationResultRecord result : quality) {
            String key = "live:evaluation:" + evaluationRunId + ":" + result.metric();
            TaskResultManifest.ArtifactMetadata artifact = artifactFor(result.metric(), manifest);
            String manifestRef = "result-manifest:" + runId;
            String summary = shortSafeSummary(result.findingSummary(), result.metric() + " " + result.status().name().toLowerCase(Locale.ROOT));
            append(context, event(context, projectId, taskId, runId, sequence++, "QUALITY_CHECK", artifact,
                    manifestRef, result.metric(), result.metric(), null, result.status().name(),
                    result.score() == null ? null : result.score().doubleValue(), null, summary,
                    "runtime:evaluation:" + evaluationRunId, key, occurredAt));
        }
        boolean failed = quality.stream().anyMatch(result -> result.status() == EvaluationResultRecord.Status.FAILED);
        boolean inconclusive = quality.isEmpty()
                || quality.stream().anyMatch(result -> result.status() == EvaluationResultRecord.Status.INCONCLUSIVE);
        String finalStatus = failed ? "FAILED" : inconclusive ? "INCONCLUSIVE" : "PASSED";
        String finalKey = "live:evaluation:" + evaluationRunId + ":artifact-final-status";
        append(context, event(context, projectId, taskId, runId, sequence, "FINAL_STATUS", null,
                "result-manifest:" + runId, "artifact_quality", null, null, finalStatus, null, null,
                inconclusive && !failed ? "Artifact quality evidence is incomplete" : "Artifact quality checks summarized",
                "runtime:evaluation:" + evaluationRunId, finalKey, occurredAt));
    }

    private void append(ExecutionContext context, ArtifactQualityEvent event) {
        try {
            boolean inserted = repository.append(context, event);
            capture(event, inserted ? "inserted" : "duplicate");
        } catch (RuntimeException failure) {
            capture(event, "failed");
            // Recording is observational: failure must not alter the evaluated result or task transition.
            LOG.log(Level.WARNING, "Artifact quality event capture failed type={0}", failure.getClass().getSimpleName());
        }
    }

    private void capture(ArtifactQualityEvent event, String result) {
        meters.counter("agentteams.artifact_quality.capture.events", "event_type", event.eventType(), "result", result).increment();
    }

    private static ArtifactQualityEvent event(ExecutionContext context, UUID projectId, UUID taskId, UUID runId,
            long sequence, String type, TaskResultManifest.ArtifactMetadata artifact, String manifestRef,
            String category, String checkItem, String actionSource, String status, Double score, Double scoreDelta, String summary,
            String sourceRef, String key, Instant occurredAt) {
        UUID fileId = artifact == null ? null : artifact.sourceFileId();
        String digest = artifact == null ? null : artifact.sha256();
        String name = artifact == null ? null : artifact.name();
        return event(context, projectId, taskId, runId, sequence, type, name, fileId, digest, manifestRef,
                category, checkItem, actionSource, status, score, scoreDelta, summary, sourceRef, key, occurredAt);
    }

    private static ArtifactQualityEvent event(ExecutionContext context, UUID projectId, UUID taskId, UUID runId,
            long sequence, String type, String name, UUID fileId, String digest, String manifestRef,
            String category, String checkItem, String actionSource, String status, Double score, Double scoreDelta,
            String summary, String sourceRef, String key, Instant occurredAt) {
        UUID id = UUID.nameUUIDFromBytes((context.organizationId() + ":" + context.tenantId() + ":"
                + context.projectId() + ":" + key).getBytes(StandardCharsets.UTF_8));
        return new ArtifactQualityEvent(id, context.organizationId(), context.tenantId(), projectId, taskId, runId,
                sequence, type, name, fileId, manifestRef, digest, category, checkItem, actionSource, status, score, scoreDelta,
                summary, "LIVE", sourceRef, "HIGH", key, occurredAt);
    }

    private static boolean qualityMetric(EvaluationResultRecord result) {
        return result.metric().startsWith("artifact_") || result.metric().equals("source_citation");
    }

    private static TaskResultManifest.ArtifactMetadata artifactFor(String metric, TaskResultManifest manifest) {
        if (manifest == null || manifest.artifacts().isEmpty()) return null;
        List<TaskResultManifest.ArtifactMetadata> pdfs = manifest.artifacts().stream()
                .filter(item -> item.name().toLowerCase(Locale.ROOT).endsWith(".pdf")
                        || "application/pdf".equalsIgnoreCase(item.contentType())).toList();
        return pdfs.size() == 1 ? pdfs.get(0) : null;
    }

    private static String shortSafeSummary(String value, String fallback) {
        String summary = value == null || value.isBlank() ? fallback : value.trim();
        summary = SENSITIVE.matcher(summary).replaceAll("[REDACTED]");
        return summary.length() <= 500 ? summary : summary.substring(0, 497) + "...";
    }
}
