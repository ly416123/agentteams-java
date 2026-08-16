package io.agentteams.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Parsed, validated payload carried by a TaskAssigned outbox event. */
public record TaskAssignedCommandPayload(
        UUID taskId,
        String agentId,
        UUID attemptId,
        UUID assignmentId,
        UUID leaseId,
        JsonNode spec,
        Map<String, JsonNode> extensions) {

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
    }
}
