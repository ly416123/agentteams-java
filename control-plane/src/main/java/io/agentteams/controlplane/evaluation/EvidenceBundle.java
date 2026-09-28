package io.agentteams.controlplane.evaluation;

import io.agentteams.application.api.TaskProcessEvent;
import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.task.TaskDecisionRecord;
import io.agentteams.controlplane.task.TaskTreeNode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable, metadata-first input contract for deterministic and semantic evaluation. */
public record EvidenceBundle(UUID taskId, UUID executionRunId, TaskSnapshot taskSnapshot,
        List<TaskProcessEvent> taskEvents, List<TaskTreeNode> taskTree, List<TaskDecisionRecord> decisions,
        List<ToolCallSummary> toolCallSummaries, TaskResultManifest resultManifest, List<ArtifactRef> artifactRefs,
        List<ApprovalRecord> approvalHistory, AgentMetadata agentMetadata, List<PolicyOutcome> policyOutcomes) {
    public EvidenceBundle {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(executionRunId, "executionRunId");
        Objects.requireNonNull(taskSnapshot, "taskSnapshot");
        taskEvents = List.copyOf(Objects.requireNonNull(taskEvents, "taskEvents"));
        taskTree = List.copyOf(Objects.requireNonNull(taskTree, "taskTree"));
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
        toolCallSummaries = List.copyOf(Objects.requireNonNull(toolCallSummaries, "toolCallSummaries"));
        artifactRefs = List.copyOf(Objects.requireNonNull(artifactRefs, "artifactRefs"));
        approvalHistory = List.copyOf(Objects.requireNonNull(approvalHistory, "approvalHistory"));
        Objects.requireNonNull(agentMetadata, "agentMetadata");
        policyOutcomes = List.copyOf(Objects.requireNonNull(policyOutcomes, "policyOutcomes"));
        if (resultManifest != null && (!taskId.equals(resultManifest.taskId())
                || !executionRunId.equals(resultManifest.runId()))) {
            throw new IllegalArgumentException("resultManifest must belong to the bundle task and run");
        }
    }

    public InputStatus inputStatus() {
        return resultManifest == null ? InputStatus.INCONCLUSIVE : InputStatus.COMPLETE;
    }

    public enum InputStatus { COMPLETE, INCONCLUSIVE }

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
