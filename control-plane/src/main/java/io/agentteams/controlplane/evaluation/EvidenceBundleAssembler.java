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
import io.agentteams.controlplane.task.TaskSourceEvidence;
import io.agentteams.controlplane.task.TaskSourceEvidenceRepository;
import io.agentteams.controlplane.task.TaskTreeNode;
import io.agentteams.controlplane.task.TaskTreeRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
    private final TaskSourceEvidenceRepository sourceEvidence;

    public EvidenceBundleAssembler(TaskRunObservationRepository runs, TaskProcessEventRepository events,
            TaskTreeRepository tree, TaskDecisionRecordRepository decisions,
            TaskResultManifestRepository manifests) {
        this(runs, events, tree, decisions, manifests, emptySourceRepository());
    }

    private static TaskSourceEvidenceRepository emptySourceRepository() {
        return new TaskSourceEvidenceRepository() {
                    @Override public java.util.UUID activeRun(ExecutionContext ignored, java.util.UUID ignoredTask) {
                        throw new UnsupportedOperationException();
                    }
                    @Override public boolean isActiveExecution(java.util.UUID ignoredTask, java.util.UUID ignoredAttempt,
                            java.util.UUID ignoredLease, java.time.Instant ignoredAt) { return false; }
                    @Override public void save(ExecutionContext ignored, java.util.UUID ignoredTask,
                            java.util.UUID ignoredRun, java.util.UUID ignoredAttempt, String ignoredCall,
                            String ignoredProvider, String ignoredIdempotency, List<TaskSourceEvidence.Source> ignoredSources) { }
                    @Override public TaskSourceEvidence find(ExecutionContext ignored, java.util.UUID ignoredTask,
                            java.util.UUID ignoredRun, int ignoredCalls, int ignoredSources) {
                        return new TaskSourceEvidence(ignoredTask, ignoredRun, List.of(), false, 0, 0);
                    }
                };
    }

    public EvidenceBundleAssembler(TaskRunObservationRepository runs, TaskProcessEventRepository events,
            TaskTreeRepository tree, TaskDecisionRecordRepository decisions,
            TaskResultManifestRepository manifests, TaskSourceEvidenceRepository sourceEvidence) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.events = Objects.requireNonNull(events, "events");
        this.tree = Objects.requireNonNull(tree, "tree");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.sourceEvidence = Objects.requireNonNull(sourceEvidence, "sourceEvidence");
    }

    public EvidenceBundle assemble(ExecutionContext context, UUID taskId, UUID runId,
            Set<TaskEventVisibility> visible) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(visible, "visible");
        Set<TaskEventVisibility> allowed = Set.copyOf(visible);
        Optional<TaskRunObservationRepository.TaskRunSnapshot> taskRun = runs.snapshotForRun(context, taskId, runId);
        List<TaskProcessEvent> observedEvents = events.find(context, taskId, runId, 0, allowed, EVENT_LIMIT + 1).stream()
                .map(EvidenceBundleAssembler::sanitizeEvent)
                .toList();
        int observedEventCount = Math.min(observedEvents.size(), EVENT_LIMIT + 1);
        List<TaskProcessEvent> safeEvents = observedEvents.stream().limit(EVENT_LIMIT).toList();
        List<TaskTreeNode> taskTree = List.copyOf(tree.find(context, runId));
        if (taskTree.stream().noneMatch(node -> taskId.equals(node.parentTaskId()))) {
            // Team aggregation runs do not write a second task_subtasks projection.
            // Keep the child handoff attached to the evidence for the same parent task.
            taskTree = List.copyOf(tree.findLatestForParent(context, taskId));
        }
        List<TaskDecisionRecord> safeDecisions = List.copyOf(decisions.find(context, taskId, runId, allowed));
        Optional<TaskResultManifest> manifest = manifests.find(context, taskId, runId, allowed).map(
                EvidenceBundleAssembler::sanitizeManifest);
        EvidenceBundle.TaskSnapshot snapshot = snapshot(taskId, taskRun);
        EvidenceBundle.RunSnapshot runSnapshot = taskRun.filter(value -> value.runStatus() != null)
                .map(value -> new EvidenceBundle.RunSnapshot(value.runStatus(), value.startedAt(), value.completedAt()))
                .orElseGet(() -> new EvidenceBundle.RunSnapshot("UNKNOWN", null, null));
        String completenessStatus = observedEventCount > EVENT_LIMIT ? "TRUNCATED"
                : taskRun.isEmpty() || taskRun.get().runStatus() == null || manifest.isEmpty()
                        ? "INCONCLUSIVE" : "COMPLETE";
        EvidenceBundle.EvidenceCompleteness completeness = new EvidenceBundle.EvidenceCompleteness(
                completenessStatus, observedEventCount, EVENT_LIMIT);
        TaskSourceEvidence ownSources = sourceEvidence.find(context, taskId, runId, 500, 10_000);
        List<TaskSourceEvidence.SearchCall> sourceCalls = new ArrayList<>(ownSources.calls());
        long observedSearchCalls = countSearchCalls(safeEvents);
        boolean sourceTruncated = ownSources.truncated() || observedEventCount > EVENT_LIMIT;
        boolean sourceAssociationIncomplete = false;
        Instant parentStartedAt = taskRun.map(TaskRunObservationRepository.TaskRunSnapshot::startedAt)
                .orElse(null);
        Set<UUID> childTasks = new HashSet<>();
        for (TaskTreeNode node : taskTree) {
            if (node.taskId().equals(taskId) || !childTasks.add(node.taskId())) continue;
            if (parentStartedAt == null || node.updatedAt() == null || node.updatedAt().isAfter(parentStartedAt)) {
                sourceAssociationIncomplete = true;
                continue;
            }
            Optional<UUID> childRun = runs.latestRunId(node.taskId());
            if (childRun.isEmpty()) continue;
            Optional<TaskRunObservationRepository.TaskRunSnapshot> childSnapshot =
                    runs.snapshotForRun(context, node.taskId(), childRun.get());
            if (childSnapshot.isEmpty() || !"SUCCEEDED".equalsIgnoreCase(childSnapshot.get().taskPhase())
                    || !"SUCCEEDED".equalsIgnoreCase(childSnapshot.get().runStatus())) continue;
            Instant childCompletedAt = childSnapshot.get().completedAt();
            if (parentStartedAt == null || childCompletedAt == null || childCompletedAt.isAfter(parentStartedAt)) {
                sourceAssociationIncomplete = true;
                continue;
            }
            TaskSourceEvidence childSources = sourceEvidence.find(context, node.taskId(), childRun.get(), 500, 10_000);
            sourceCalls.addAll(childSources.calls());
            List<TaskProcessEvent> childEvents = events.find(context, node.taskId(), childRun.get(), 0, allowed,
                    EVENT_LIMIT + 1);
            sourceTruncated |= childSources.truncated() || childEvents.size() > EVENT_LIMIT;
            observedSearchCalls += countSearchCalls(childEvents.stream().limit(EVENT_LIMIT).toList());
        }
        int observedCallCount = Math.toIntExact(observedSearchCalls);
        int observedSourceCount = sourceCalls.stream().mapToInt(call -> call.sources().size()).sum();
        TaskSourceEvidence sources = new TaskSourceEvidence(taskId, runId, sourceCalls, sourceTruncated,
                observedCallCount, observedSourceCount);
        boolean sourceComplete = !sourceAssociationIncomplete && !sources.truncated()
                && sources.observedCallCount() == sources.calls().size()
                && !sources.calls().isEmpty() && sources.calls().stream().allMatch(TaskSourceEvidence.SearchCall::complete);
        EvidenceBundle.SourceEvidenceCompleteness sourceCompleteness = new EvidenceBundle.SourceEvidenceCompleteness(
                sourceComplete ? "COMPLETE" : sources.truncated() ? "TRUNCATED" : "INCONCLUSIVE",
                observedSearchCalls > 0 ? (int) observedSearchCalls : sources.observedCallCount(), sources.calls().size(),
                sources.observedSourceCount(), 10_000);
        return new EvidenceBundle(taskId, runId, snapshot, runSnapshot, completeness, safeEvents, taskTree, safeDecisions,
                summarizeTools(safeEvents), manifest.orElse(null), artifactRefs(manifest), List.of(),
                new EvidenceBundle.AgentMetadata("unknown", "unknown", "unknown"), List.of(), sources, sourceCompleteness);
    }

    private static long countSearchCalls(List<TaskProcessEvent> events) {
        return events.stream().filter(event -> "tool.called".equalsIgnoreCase(event.eventType()))
                .map(TaskProcessEvent::payload).filter(Objects::nonNull).map(EvidenceBundleAssembler::parseObject)
                .map(payload -> textOrNull(payload, "tool"))
                .filter(tool -> "web_search".equalsIgnoreCase(tool)).count();
    }

    private static EvidenceBundle.TaskSnapshot snapshot(UUID taskId,
            Optional<TaskRunObservationRepository.TaskRunSnapshot> taskRun) {
        if (taskRun.isEmpty()) return new EvidenceBundle.TaskSnapshot(taskId, "UNKNOWN", "unknown", null);
        TaskRunObservationRepository.TaskRunSnapshot value = taskRun.get();
        return new EvidenceBundle.TaskSnapshot(taskId, value.taskPhase(), value.taskType(), value.sourceConversationId());
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
