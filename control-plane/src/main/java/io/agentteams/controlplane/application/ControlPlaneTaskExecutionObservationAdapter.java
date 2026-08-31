package io.agentteams.controlplane.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.application.api.ExecutionEventPort.ArtifactReference;
import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskExecutionObservationPort;
import io.agentteams.application.api.TaskProcessEvent;
import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.outbox.EventEnvelope;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.task.JdbcTaskRunObservationRepository;
import io.agentteams.controlplane.task.TaskProcessEventService;
import io.agentteams.controlplane.task.TaskRunObservationRepository;
import io.agentteams.controlplane.task.TaskResultManifestService;
import io.agentteams.controlplane.webhook.WebhookDeliveryService;
import io.agentteams.controlplane.webhook.WebhookScope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Converts validated Worker observations into durable task projections and Webhook outbox events. */
@Service
public final class ControlPlaneTaskExecutionObservationAdapter implements TaskExecutionObservationPort {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_SUMMARY_LENGTH = 4096;
    private final TaskRunObservationRepository runs;
    private final TaskProcessEventService processEvents;
    private final TaskResultManifestService results;
    private final WebhookDeliveryService webhooks;

    @Autowired
    public ControlPlaneTaskExecutionObservationAdapter(JdbcTaskRunObservationRepository runs,
            TaskProcessEventService processEvents, TaskResultManifestService results,
            WebhookDeliveryService webhooks) {
        this((TaskRunObservationRepository) runs, processEvents, results, webhooks);
    }

    ControlPlaneTaskExecutionObservationAdapter(TaskRunObservationRepository runs,
            TaskProcessEventService processEvents, TaskResultManifestService results,
            WebhookDeliveryService webhooks) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.processEvents = Objects.requireNonNull(processEvents, "processEvents");
        this.results = Objects.requireNonNull(results, "results");
        this.webhooks = Objects.requireNonNull(webhooks, "webhooks");
    }

    @Override
    @Transactional
    public void accepted(UUID taskId, UUID runId, UUID eventId, Instant occurredAt, String correlationId) {
        recordProcess(taskId, runId, eventId, occurredAt, correlationId, "task.started",
                payload("status", "RUNNING"), "RUNNING");
    }

    @Override
    @Transactional
    public void progress(UUID taskId, UUID runId, UUID eventId, Instant occurredAt, String correlationId,
            int percent, String status, String message) {
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("percent must be 0..100");
        ObjectNode body = JSON.createObjectNode().put("percent", percent)
                .put("status", safeText(status, "RUNNING"))
                .put("message", safeText(message, "progress updated"));
        recordProcess(taskId, runId, eventId, occurredAt, correlationId, "task.progress", body, "RUNNING");
    }

    @Override
    @Transactional
    public void completed(UUID taskId, UUID runId, UUID eventId, Instant occurredAt, String correlationId,
            String resultSummary, List<ArtifactReference> artifacts) {
        Objects.requireNonNull(artifacts, "artifacts");
        ExecutionContext context = recordProcess(taskId, runId, eventId, occurredAt, correlationId,
                "task.completed", payload("status", "SUCCEEDED"), "SUCCEEDED");
        if (context == null) return;
        TaskResultManifest manifest = manifest(taskId, runId, "SUCCEEDED", resultSummary, artifacts);
        results.publish(context, manifest);
        JsonNode resultPayload = manifestPayload(manifest);
        enqueueWebhook(context, "task.completed", lifecycleEventId(runId, eventId, "completed"), runId, 0,
                occurredAt, resultPayload, correlationId);
        enqueueWebhook(context, "task.result", resultEventId(runId), runId, 0, occurredAt, resultPayload, correlationId);
    }

    @Override
    @Transactional
    public void failed(UUID taskId, UUID runId, UUID eventId, Instant occurredAt, String correlationId,
            String failureCode, String failureMessage) {
        ExecutionContext context = recordProcess(taskId, runId, eventId, occurredAt, correlationId,
                "task.failed", failurePayload(failureCode, failureMessage), "FAILED");
        if (context == null) return;
        TaskResultManifest manifest = manifest(taskId, runId, "FAILED", failureMessage, List.of());
        results.publish(context, manifest);
        JsonNode resultPayload = manifestPayload(manifest);
        enqueueWebhook(context, "task.failed", lifecycleEventId(runId, eventId, "failed"), runId, 0,
                occurredAt, resultPayload, correlationId);
        enqueueWebhook(context, "task.result", resultEventId(runId), runId, 0, occurredAt, resultPayload, correlationId);
    }

    private ExecutionContext recordProcess(UUID taskId, UUID runId, UUID eventId, Instant occurredAt,
            String correlationId, String eventType, JsonNode body, String runStatus) {
        TaskExecutionObservationPort.requireCommon(taskId, runId, eventId, occurredAt, correlationId);
        Optional<ExecutionContext> resolved = runs.contextForTask(taskId);
        if (resolved.isEmpty()) {
            // Unscoped legacy/internal tasks are intentionally not projected into a tenant-visible stream.
            return null;
        }
        ExecutionContext context = resolved.get();
        runs.ensureRun(context, taskId, runId, runStatus, occurredAt);
        long sequence = runs.nextSequence(runId);
        TaskProcessEvent event = new TaskProcessEvent(eventId, taskId, runId, sequence, eventType,
                TaskEventVisibility.REQUESTER, occurredAt, correlationId, body.toString(), null);
        processEvents.append(context, event);
        enqueueWebhook(context, "task.process", event.eventId(), runId, sequence, occurredAt,
                body, correlationId);
        return context;
    }

    private void enqueueWebhook(ExecutionContext context, String eventType, UUID eventId, UUID aggregateId,
            long version, Instant occurredAt, JsonNode payload, String correlationId) {
        if (context == null) return;
        webhooks.enqueue(new WebhookScope(context.organizationId(), context.tenantId(), context.projectId()),
                new EventEnvelope(eventId, eventType, "task", aggregateId, version, occurredAt, payload,
                        correlationId, "", ""), occurredAt);
    }

    private static TaskResultManifest manifest(UUID taskId, UUID runId, String status, String summary,
            List<ArtifactReference> artifacts) {
        List<TaskResultManifest.ArtifactMetadata> metadata = artifacts.stream().map(artifact ->
                new TaskResultManifest.ArtifactMetadata(artifact.name(), artifact.storageKey(), artifact.contentType(),
                        artifact.sizeBytes(), artifact.sha256(), 0, "FINAL", TaskEventVisibility.REQUESTER)).toList();
        return new TaskResultManifest(taskId, runId, status, safeText(summary, "task " + status.toLowerCase(Locale.ROOT)), metadata);
    }

    private static JsonNode manifestPayload(TaskResultManifest manifest) {
        ObjectNode payload = JSON.createObjectNode().put("status", manifest.status())
                .put("summary", safeText(manifest.summary(), "task completed"));
        payload.putArray("artifacts").addAll(manifest.artifacts().stream().map(artifact -> JSON.createObjectNode()
                .put("name", artifact.name()).put("storageRef", artifact.storageRef())
                .put("contentType", artifact.contentType()).put("sizeBytes", artifact.sizeBytes())
                .put("sha256", artifact.sha256()).put("stage", artifact.stage())).toList());
        return payload;
    }

    private static ObjectNode payload(String key, String value) {
        return JSON.createObjectNode().put(key, value);
    }

    private static ObjectNode failurePayload(String code, String message) {
        return JSON.createObjectNode().put("status", "FAILED")
                .put("code", safeText(code, "RUNTIME_FAILURE"))
                .put("message", safeText(message, "task failed"));
    }

    private static UUID resultEventId(UUID runId) {
        return UUID.nameUUIDFromBytes(("task-result:" + runId).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID lifecycleEventId(UUID runId, UUID eventId, String type) {
        return UUID.nameUUIDFromBytes(("task-lifecycle:" + runId + ":" + eventId + ":" + type)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String safeText(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String marker : new String[] {"token", "password", "secret", "authorization", "system prompt",
                "chain of thought", "private_key", "client_secret"}) {
            if (lower.contains(marker)) return fallback;
        }
        return normalized.length() <= MAX_SUMMARY_LENGTH ? normalized : normalized.substring(0, MAX_SUMMARY_LENGTH);
    }
}
