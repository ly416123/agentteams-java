package io.agentteams.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.time.Instant;
import java.util.Optional;

/** Parsed, validated payload carried by a TaskAssigned outbox event. */
public record TaskAssignedCommandPayload(
        UUID taskId,
        String agentId,
        UUID attemptId,
        UUID assignmentId,
        UUID leaseId,
        JsonNode spec,
        Map<String, JsonNode> extensions,
        Optional<SandboxAssignmentPayload> sandbox) {

    public TaskAssignedCommandPayload(UUID taskId, String agentId, UUID attemptId, UUID assignmentId,
            UUID leaseId, JsonNode spec, Map<String, JsonNode> extensions) {
        this(taskId, agentId, attemptId, assignmentId, leaseId, spec, extensions, Optional.empty());
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
        extensions = Map.copyOf(Objects.requireNonNull(extensions, "extensions"));
        sandbox = Optional.ofNullable(sandbox).orElseGet(Optional::empty);
    }

    public record SandboxAssignmentPayload(String providerSandboxId, String profile, String status,
            String endpointRef, Instant expiresAt, UUID ownerTaskId, UUID ownerAttemptId) {
        public SandboxAssignmentPayload {
            requireText(providerSandboxId, "sandbox.providerSandboxId");
            requireText(profile, "sandbox.profile");
            requireText(status, "sandbox.status");
            requireText(endpointRef, "sandbox.endpointRef");
            Objects.requireNonNull(expiresAt, "sandbox.expiresAt");
            Objects.requireNonNull(ownerTaskId, "sandbox.ownerTaskId");
            Objects.requireNonNull(ownerAttemptId, "sandbox.ownerAttemptId");
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must be non-blank");
            }
        }
    }
}
