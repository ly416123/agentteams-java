package io.agentteams.controlplane.evaluation;

import io.agentteams.application.api.TaskProcessEvent;
import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.task.TaskDecisionRecord;
import io.agentteams.controlplane.task.TaskTreeNode;
import io.agentteams.controlplane.task.TaskSourceEvidence;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable, metadata-first input contract for deterministic and semantic evaluation. */
public record EvidenceBundle(UUID taskId, UUID executionRunId, TaskSnapshot taskSnapshot,
        RunSnapshot runSnapshot, EvidenceCompleteness evidenceCompleteness,
        List<TaskProcessEvent> taskEvents, List<TaskTreeNode> taskTree, List<TaskDecisionRecord> decisions,
        List<ToolCallSummary> toolCallSummaries, TaskResultManifest resultManifest, List<ArtifactRef> artifactRefs,
        List<ApprovalRecord> approvalHistory, AgentMetadata agentMetadata, List<PolicyOutcome> policyOutcomes,
        TaskSourceEvidence sourceEvidence, SourceEvidenceCompleteness sourceEvidenceCompleteness) {
    public EvidenceBundle {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(executionRunId, "executionRunId");
        Objects.requireNonNull(taskSnapshot, "taskSnapshot");
        Objects.requireNonNull(runSnapshot, "runSnapshot");
        Objects.requireNonNull(evidenceCompleteness, "evidenceCompleteness");
        taskEvents = List.copyOf(Objects.requireNonNull(taskEvents, "taskEvents"));
        taskTree = List.copyOf(Objects.requireNonNull(taskTree, "taskTree"));
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
        toolCallSummaries = List.copyOf(Objects.requireNonNull(toolCallSummaries, "toolCallSummaries"));
        artifactRefs = List.copyOf(Objects.requireNonNull(artifactRefs, "artifactRefs"));
        approvalHistory = List.copyOf(Objects.requireNonNull(approvalHistory, "approvalHistory"));
        Objects.requireNonNull(agentMetadata, "agentMetadata");
        policyOutcomes = List.copyOf(Objects.requireNonNull(policyOutcomes, "policyOutcomes"));
        Objects.requireNonNull(sourceEvidenceCompleteness, "sourceEvidenceCompleteness");
        if (resultManifest != null && (!taskId.equals(resultManifest.taskId())
                || !executionRunId.equals(resultManifest.runId()))) {
            throw new IllegalArgumentException("resultManifest must belong to the bundle task and run");
        }
    }

    public EvidenceBundle(UUID taskId, UUID executionRunId, TaskSnapshot taskSnapshot, RunSnapshot runSnapshot,
            EvidenceCompleteness evidenceCompleteness, List<TaskProcessEvent> taskEvents, List<TaskTreeNode> taskTree,
            List<TaskDecisionRecord> decisions, List<ToolCallSummary> toolCallSummaries,
            TaskResultManifest resultManifest, List<ArtifactRef> artifactRefs, List<ApprovalRecord> approvalHistory,
            AgentMetadata agentMetadata, List<PolicyOutcome> policyOutcomes) {
        this(taskId, executionRunId, taskSnapshot, runSnapshot, evidenceCompleteness, taskEvents, taskTree, decisions,
                toolCallSummaries, resultManifest, artifactRefs, approvalHistory, agentMetadata, policyOutcomes, null,
                new SourceEvidenceCompleteness("INCONCLUSIVE", 0, 0, 0, 10_000));
    }

    /** Source-compatible constructor for callers creating legacy/incomplete bundles directly. */
    public EvidenceBundle(UUID taskId, UUID executionRunId, TaskSnapshot taskSnapshot,
            List<TaskProcessEvent> taskEvents, List<TaskTreeNode> taskTree, List<TaskDecisionRecord> decisions,
            List<ToolCallSummary> toolCallSummaries, TaskResultManifest resultManifest, List<ArtifactRef> artifactRefs,
            List<ApprovalRecord> approvalHistory, AgentMetadata agentMetadata, List<PolicyOutcome> policyOutcomes) {
        this(taskId, executionRunId, taskSnapshot, new RunSnapshot("UNKNOWN", null, null),
                new EvidenceCompleteness("INCONCLUSIVE", taskEvents == null ? 0 : taskEvents.size(), 1000),
                taskEvents, taskTree, decisions, toolCallSummaries, resultManifest, artifactRefs, approvalHistory,
                agentMetadata, policyOutcomes, null, new SourceEvidenceCompleteness("INCONCLUSIVE", 0, 0, 0, 10_000));
    }

    /** Reports whether the assembled evidence is usable as a complete input, including Run and truncation checks. */
    public InputStatus inputStatus() {
        return "COMPLETE".equals(evidenceCompleteness.status()) ? InputStatus.COMPLETE : InputStatus.INCONCLUSIVE;
    }

    public enum InputStatus { COMPLETE, INCONCLUSIVE }

    public record SourceEvidenceCompleteness(String status, int observedSearchCalls, int recordedSearchCalls,
            int observedSources, int sourceLimit) {
        public SourceEvidenceCompleteness {
            status = required(status, "status").toUpperCase(java.util.Locale.ROOT);
            if (!Set.of("COMPLETE", "TRUNCATED", "INCONCLUSIVE").contains(status)
                    || observedSearchCalls < 0 || recordedSearchCalls < 0 || observedSources < 0 || sourceLimit < 1) {
                throw new IllegalArgumentException("source evidence completeness is invalid");
            }
        }
    }

    public record RunSnapshot(String status, Instant startedAt, Instant completedAt) {
        private static final Set<String> ALLOWED = Set.of("UNKNOWN", "QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED");
        public RunSnapshot {
            status = required(status, "status").toUpperCase(java.util.Locale.ROOT);
            if (!ALLOWED.contains(status)) throw new IllegalArgumentException("unsupported run status");
        }
    }

    public record EvidenceCompleteness(String status, int observedEventCount, int eventLimit) {
        private static final Set<String> ALLOWED = Set.of("COMPLETE", "TRUNCATED", "INCONCLUSIVE");
        public EvidenceCompleteness {
            status = required(status, "status").toUpperCase(java.util.Locale.ROOT);
            if (!ALLOWED.contains(status)) throw new IllegalArgumentException("unsupported evidence completeness status");
            if (observedEventCount < 0) throw new IllegalArgumentException("observedEventCount must not be negative");
            if (eventLimit < 1) throw new IllegalArgumentException("eventLimit must be positive");
            if ("TRUNCATED".equals(status) && observedEventCount != eventLimit + 1) {
                throw new IllegalArgumentException("TRUNCATED requires observing exactly one event beyond the limit");
            }
            if ("COMPLETE".equals(status) && observedEventCount >= eventLimit + 1) {
                throw new IllegalArgumentException("COMPLETE requires fewer than limit + 1 observed events");
            }
        }
    }

    public record TaskSnapshot(UUID taskId, String phase, String taskType, String sourceConversationId) {
        public TaskSnapshot {
            Objects.requireNonNull(taskId, "taskId");
            phase = required(phase, "phase");
            taskType = required(taskType, "taskType");
            sourceConversationId = sourceConversationId == null ? null : sourceConversationId.trim();
        }
    }

    public record ToolCallSummary(String toolName, long count, String visibility) {
        public ToolCallSummary {
            toolName = required(toolName, "toolName");
            visibility = required(visibility, "visibility");
            if (count < 0) throw new IllegalArgumentException("count must not be negative");
        }
    }

    public record ArtifactRef(String artifactId, String storageRef, String contentType, long sizeBytes, String sha256) {
        public ArtifactRef {
            artifactId = required(artifactId, "artifactId");
            storageRef = required(storageRef, "storageRef");
            contentType = required(contentType, "contentType");
            sha256 = required(sha256, "sha256");
            if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes must not be negative");
            if (storageRef.contains("?") || storageRef.toLowerCase().contains("x-amz-")) {
                throw new IllegalArgumentException("storageRef must not be a presigned URL");
            }
        }
    }

    public record ApprovalRecord(String action, String status, String actor) {
        public ApprovalRecord {
            action = required(action, "action");
            status = required(status, "status");
            actor = required(actor, "actor");
        }
    }

    public record AgentMetadata(String runtime, String model, String version) {
        public AgentMetadata {
            runtime = required(runtime, "runtime");
            model = required(model, "model");
            version = required(version, "version");
        }
    }

    public record PolicyOutcome(String policy, String decision, String reason) {
        public PolicyOutcome {
            policy = required(policy, "policy");
            decision = required(decision, "decision");
            reason = reason == null ? "" : reason.trim();
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
