package io.agentteams.worker.agentscope;

import java.util.Objects;

/** Project-owned event boundary for AgentScope execution events. */
public record AgentScopeExecutionEvent(
        String eventId,
        Kind kind,
        String safeMessage,
        boolean terminal,
        boolean success,
        boolean duplicate) {

    public AgentScopeExecutionEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(safeMessage, "safeMessage");
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
        IGNORED,
        UNMAPPED
    }
}
