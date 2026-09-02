package io.agentteams.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Objects;
import java.time.Instant;
import java.util.UUID;

/** Parsed, validated payload carried by a TaskAssigned outbox event. */
public record TaskAssignedCommandPayload(
        UUID taskId,
        String agentId,
        UUID attemptId,
        UUID assignmentId,
        UUID leaseId,
        JsonNode spec,
        SandboxAssignmentPayload sandbox,
        JsonNode memoryContext,
        Map<String, JsonNode> extensions) {

    public TaskAssignedCommandPayload(UUID taskId, String agentId, UUID attemptId, UUID assignmentId,
            UUID leaseId, JsonNode spec, Map<String, JsonNode> extensions) {
        this(taskId, agentId, attemptId, assignmentId, leaseId, spec, null, null, extensions);
    }

    public TaskAssignedCommandPayload(UUID taskId, String agentId, UUID attemptId, UUID assignmentId,
            UUID leaseId, JsonNode spec, SandboxAssignmentPayload sandbox, Map<String, JsonNode> extensions) {
        this(taskId, agentId, attemptId, assignmentId, leaseId, spec, sandbox, null, extensions);
    }

    public TaskAssignedCommandPayload {
        Objects.requireNonNull(taskId, "taskId");
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(assignmentId, "assignmentId");
        Objects.requireNonNull(leaseId, "leaseId");
        Objects.requireNonNull(spec, "spec");
        if (!spec.isObject()) {
            throw new IllegalArgumentException("spec must be a JSON object");
        }
        if (sandbox != null && !taskId.equals(sandbox.ownerTaskId())) {
            throw new IllegalArgumentException("sandbox ownerTaskId does not match taskId");
        }
        if (sandbox != null && !attemptId.equals(sandbox.ownerAttemptId())) {
            throw new IllegalArgumentException("sandbox ownerAttemptId does not match attemptId");
        }
        if (memoryContext != null && !memoryContext.isArray()) {
            throw new IllegalArgumentException("memoryContext must be an array");
        }
        extensions = Map.copyOf(Objects.requireNonNull(extensions, "extensions"));
    }

    /** Validated, credential-free sandbox data copied to the Agent channel. */
    public record SandboxAssignmentPayload(String sandboxId, String providerSandboxId, String profile,
            String status, String endpointRef, Instant expiresAt, UUID ownerTaskId, UUID ownerAttemptId) {
        public SandboxAssignmentPayload {
            if (sandboxId == null || sandboxId.isBlank()) {
                throw new IllegalArgumentException("sandboxId must not be blank");
            }
            if (providerSandboxId == null || providerSandboxId.isBlank()) {
                throw new IllegalArgumentException("providerSandboxId must not be blank");
            }
            profile = required(profile, "profile");
            status = required(status, "status");
            endpointRef = required(endpointRef, "endpointRef");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(ownerTaskId, "ownerTaskId");
            Objects.requireNonNull(ownerAttemptId, "ownerAttemptId");
        }

        private static String required(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value.trim();
        }
    }
}
