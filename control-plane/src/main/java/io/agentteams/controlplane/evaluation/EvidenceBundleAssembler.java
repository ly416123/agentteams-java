package io.agentteams.controlplane.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskProcessEvent;
import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.task.TaskDecisionRecord;
import io.agentteams.controlplane.task.TaskDecisionRecordRepository;
import io.agentteams.controlplane.task.TaskProcessEventRepository;
import io.agentteams.controlplane.task.TaskResultManifestRepository;
import io.agentteams.controlplane.task.TaskRunObservationRepository;
import io.agentteams.controlplane.task.TaskTreeNode;
import io.agentteams.controlplane.task.TaskTreeRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Builds a permission-filtered, metadata-first evaluation input without changing task facts. */
public final class EvidenceBundleAssembler {
    private static final int MAX_INLINE_PAYLOAD_BYTES = 16 * 1024;
    private static final int EVENT_LIMIT = 1000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SENSITIVE_MARKERS = Set.of(
            "token", "password", "cookie", "secret", "authorization", "api_key", "apikey");

    private final TaskRunObservationRepository runs;
    private final TaskProcessEventRepository events;
    private final TaskTreeRepository tree;
    private final TaskDecisionRecordRepository decisions;
    private final TaskResultManifestRepository manifests;

    public EvidenceBundleAssembler(TaskRunObservationRepository runs, TaskProcessEventRepository events,
            TaskTreeRepository tree, TaskDecisionRecordRepository decisions,
            TaskResultManifestRepository manifests) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.events = Objects.requireNonNull(events, "events");
        this.tree = Objects.requireNonNull(tree, "tree");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
    }

    public EvidenceBundle assemble(ExecutionContext context, UUID taskId, UUID runId,
            Set<TaskEventVisibility> visible) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(visible, "visible");
        Set<TaskEventVisibility> allowed = Set.copyOf(visible);
        TaskRunObservationRepository.TaskPlanningSnapshot planning = runs.planningForTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("task planning snapshot is not available"));
        List<TaskProcessEvent> safeEvents = events.find(context, taskId, runId, 0, allowed, EVENT_LIMIT).stream()
                .map(EvidenceBundleAssembler::sanitizeEvent)
                .toList();
        List<TaskTreeNode> taskTree = List.copyOf(tree.find(context, runId));
        List<TaskDecisionRecord> safeDecisions = List.copyOf(decisions.find(context, taskId, runId, allowed));
        Optional<TaskResultManifest> manifest = manifests.find(context, taskId, runId, allowed).map(
                EvidenceBundleAssembler::sanitizeManifest);
        EvidenceBundle.TaskSnapshot snapshot = snapshot(taskId, planning, manifest);
        return new EvidenceBundle(taskId, runId, snapshot, safeEvents, taskTree, safeDecisions,
                summarizeTools(safeEvents), manifest.orElse(null), artifactRefs(manifest), List.of(),
                new EvidenceBundle.AgentMetadata("unknown", "unknown", "unknown"), List.of());
    }

    private static EvidenceBundle.TaskSnapshot snapshot(UUID taskId,
            TaskRunObservationRepository.TaskPlanningSnapshot planning, Optional<TaskResultManifest> manifest) {
        JsonNode spec = parseObject(planning.specJson());
        String taskType = textOr(spec, "taskType", "unknown");
        String conversation = textOrNull(spec, "sourceConversationId");
        String phase = manifest.map(TaskResultManifest::status).orElse("UNKNOWN");
        return new EvidenceBundle.TaskSnapshot(taskId, phase, taskType, conversation);
    }

    private static List<EvidenceBundle.ToolCallSummary> summarizeTools(List<TaskProcessEvent> events) {
        Map<String, Long> counts = new HashMap<>();
        for (TaskProcessEvent event : events) {
            if (!"tool.called".equalsIgnoreCase(event.eventType()) || event.payload() == null) continue;
            JsonNode payload = parseObject(event.payload());
            String tool = textOrNull(payload, "tool");
            if (tool != null) counts.merge(tool, 1L, Long::sum);
        }
        return counts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new EvidenceBundle.ToolCallSummary(entry.getKey(), entry.getValue(), "VISIBLE"))
                .toList();
    }

    private static List<EvidenceBundle.ArtifactRef> artifactRefs(Optional<TaskResultManifest> manifest) {
        if (manifest.isEmpty()) return List.of();
        return manifest.get().artifacts().stream()
                .map(artifact -> new EvidenceBundle.ArtifactRef(artifact.name(), artifact.storageRef(),
                        artifact.contentType(), artifact.sizeBytes(), artifact.sha256()))
                .toList();
    }

    private static TaskProcessEvent sanitizeEvent(TaskProcessEvent event) {
        String payload = event.payload();
        if (payload == null) return event;
        boolean tooLarge = payload.getBytes(StandardCharsets.UTF_8).length > MAX_INLINE_PAYLOAD_BYTES;
        boolean sensitive = containsSensitiveMaterial(payload);
        if (!tooLarge && !sensitive) return event;
        String reference = event.payloadRef() == null ? "task-process:" + event.eventId() : event.payloadRef();
        return new TaskProcessEvent(event.eventId(), event.taskId(), event.runId(), event.sequence(), event.eventType(),
                event.visibility(), event.occurredAt(), event.correlationId(), null, reference);
    }

    private static TaskResultManifest sanitizeManifest(TaskResultManifest manifest) {
        List<TaskResultManifest.ArtifactMetadata> artifacts = manifest.artifacts().stream().map(artifact -> {
            String storageRef = artifact.storageRef();
            if (storageRef.contains("?") || storageRef.toLowerCase(Locale.ROOT).contains("x-amz-")) {
                storageRef = "artifact:" + artifact.sha256();
            }
            return new TaskResultManifest.ArtifactMetadata(artifact.name(), storageRef, artifact.contentType(),
                    artifact.sizeBytes(), artifact.sha256(), artifact.version(), artifact.stage(), artifact.visibility());
        }).toList();
        String summary = containsSensitiveMaterial(manifest.summary()) ? "[REDACTED]" : manifest.summary();
        return new TaskResultManifest(manifest.taskId(), manifest.runId(), manifest.status(), summary, artifacts);
    }

    private static JsonNode parseObject(String value) {
        try {
            JsonNode parsed = JSON.readTree(value);
            return parsed != null && parsed.isObject() ? parsed : JSON.createObjectNode();
        } catch (java.io.IOException error) {
            return JSON.createObjectNode();
        }
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = textOrNull(node, field);
        return value == null ? fallback : value;
    }

    private static String textOrNull(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean containsSensitiveMaterial(String payload) {
        String normalized = payload.toLowerCase(Locale.ROOT).replace('-', '_');
        return SENSITIVE_MARKERS.stream().anyMatch(normalized::contains);
    }
}
