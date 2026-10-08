package io.agentteams.worker.agentscope;

import java.util.Objects;

/** Project-owned event boundary for AgentScope execution events. */
public record AgentScopeExecutionEvent(
        String taskId,
        String attemptId,
        String leaseId,
        String eventId,
        String correlationId,
        String runtime,
        Kind kind,
        String safeMessage,
        boolean terminal,
        boolean success,
        boolean duplicate,
        String safeToolCallId) {

    public AgentScopeExecutionEvent(String taskId, String attemptId, String leaseId, String eventId,
            String correlationId, String runtime, Kind kind, String safeMessage,
            boolean terminal, boolean success, boolean duplicate) {
        this(taskId, attemptId, leaseId, eventId, correlationId, runtime, kind, safeMessage,
                terminal, success, duplicate, null);
    }

    public AgentScopeExecutionEvent {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (attemptId == null || attemptId.isBlank()) {
            throw new IllegalArgumentException("attemptId must not be blank");
        }
        if (leaseId == null || leaseId.isBlank()) {
            throw new IllegalArgumentException("leaseId must not be blank");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        if (runtime == null || runtime.isBlank()) {
            throw new IllegalArgumentException("runtime must not be blank");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(safeMessage, "safeMessage");
        if (safeToolCallId != null && safeToolCallId.isBlank()) {
            throw new IllegalArgumentException("safeToolCallId must not be blank when present");
        }
    }

    public enum Kind {
        AGENT_STARTED,
        MODEL_CALL_STARTED,
        MODEL_CALL_COMPLETED,
        TEXT_DELTA,
        TOOL_CALL_STARTED,
        TOOL_CALL_COMPLETED,
        AGENT_RESULT,
        AGENT_ENDED,
        ERROR,
        STALE,
        IGNORED,
        UNMAPPED
    }
}
