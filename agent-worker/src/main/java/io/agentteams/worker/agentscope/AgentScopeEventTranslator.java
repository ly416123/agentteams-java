package io.agentteams.worker.agentscope;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Converts AgentScope events into a small, project-owned execution event model. */
public final class AgentScopeEventTranslator {
    private static final int MAX_SAFE_MESSAGE_LENGTH = 128;
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?:authorization|api[-_ ]?key|token|password|secret)\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;]+"
                    + "|bearer\\s+[^\\s,;]+|sk-[A-Za-z0-9_-]{8,}",
            Pattern.CASE_INSENSITIVE);
    private final Set<String> seenEventIds = ConcurrentHashMap.newKeySet();

    public AgentScopeExecutionEvent translate(AgentEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        String eventId = event.getId();
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("AgentScope event id must not be blank");
        }
        boolean duplicate = !seenEventIds.add(eventId);

        if (event instanceof AgentStartEvent) {
            return mapped(eventId, AgentScopeExecutionEvent.Kind.AGENT_STARTED,
                    "agent started", false, true, duplicate);
        }
        if (event instanceof ModelCallStartEvent) {
            return mapped(eventId, AgentScopeExecutionEvent.Kind.MODEL_CALL_STARTED,
                    "model call started", false, true, duplicate);
        }
        if (event instanceof ModelCallEndEvent) {
            return mapped(eventId, AgentScopeExecutionEvent.Kind.MODEL_CALL_COMPLETED,
                    "model call completed", false, true, duplicate);
        }
        if (event instanceof TextBlockDeltaEvent textDelta) {
            return mapped(eventId, AgentScopeExecutionEvent.Kind.TEXT_DELTA,
                    "text " + safeText(textDelta.getDelta()), false, true, duplicate);
        }
        if (event instanceof ToolCallStartEvent toolStart) {
            return mapped(eventId, AgentScopeExecutionEvent.Kind.TOOL_CALL_STARTED,
                    "tool " + safeToolName(toolStart.getToolCallName()) + " started",
                    false, true, duplicate);
        }
        if (event instanceof ToolCallEndEvent toolEnd) {
            return mapped(eventId, AgentScopeExecutionEvent.Kind.TOOL_CALL_COMPLETED,
                    "tool " + safeToolName(toolEnd.getToolCallName()) + " completed",
                    false, true, duplicate);
        }
        if (event instanceof AgentResultEvent) {
            return mapped(eventId, AgentScopeExecutionEvent.Kind.AGENT_RESULT,
                    "agent result", true, true, duplicate);
        }
        if (event instanceof AgentEndEvent) {
            return mapped(eventId, AgentScopeExecutionEvent.Kind.AGENT_ENDED,
                    "agent ended", true, true, duplicate);
        }
        if (event instanceof ExceedMaxItersEvent
                || event instanceof AllToolsDeniedEvent
                || event instanceof RequestStopEvent) {
            return mapped(eventId, AgentScopeExecutionEvent.Kind.ERROR,
                    "agent execution error", true, false, duplicate);
        }
        return mapped(eventId, AgentScopeExecutionEvent.Kind.UNMAPPED,
                "unmapped AgentScope event", false, true, duplicate);
    }

    private static AgentScopeExecutionEvent mapped(String eventId, AgentScopeExecutionEvent.Kind kind,
            String safeMessage, boolean terminal, boolean success, boolean duplicate) {
        return new AgentScopeExecutionEvent(eventId, kind, safeMessage, terminal, success, duplicate);
    }

    private static String safeText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String redacted = SENSITIVE_VALUE.matcher(value).replaceAll("[REDACTED]");
        return truncate(redacted);
    }

    private static String safeToolName(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String candidate = value.trim();
        int boundary = firstBoundary(candidate);
        if (boundary >= 0) {
            candidate = candidate.substring(0, boundary);
        }
        candidate = candidate.replaceAll("[^A-Za-z0-9_.:-]", "_");
        candidate = truncate(candidate);
        return candidate.isBlank() ? "unknown" : candidate;
    }

    private static int firstBoundary(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isWhitespace(current) || current == '(' || current == '{' || current == '[') {
                return index;
            }
        }
        return -1;
    }

    private static String truncate(String value) {
        if (value.length() <= MAX_SAFE_MESSAGE_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_SAFE_MESSAGE_LENGTH);
    }
}
