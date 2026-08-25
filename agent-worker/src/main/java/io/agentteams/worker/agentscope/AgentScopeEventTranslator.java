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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Converts AgentScope events into a small, project-owned execution event model.
 *
 * <p>Before calling this translator, the Worker must inject the current Attempt
 * context with {@code AgentEvent.withMetadataEntry("attemptId", currentAttemptId)}.
 * Task 3 must preserve this requirement when it builds the AgentScope runtime
 * event pipeline; events without this metadata are stale and are not accepted.
 */
public final class AgentScopeEventTranslator {
    private static final int MAX_SAFE_MESSAGE_LENGTH = 128;
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "\\\"(?:authorization|apiKey|api[-_ ]?key|token|password|secret)\\\"\\s*:\\s*\\\""
                    + "(?:bearer\\s+)?[^\\\"]*\\\""
                    + "|(?:authorization|apiKey|api[-_ ]?key|token|password|secret)\\s*[:=]\\s*"
                    + "(?:bearer\\s+)?[^\\s,;]+"
                    + "|bearer\\s+[^\\s,;]+|sk-[A-Za-z0-9_-]{8,}",
            Pattern.CASE_INSENSITIVE);
    private final String taskId;
    private final String attemptId;
    private final String leaseId;
    private final String correlationId;
    private final String runtime;
    private final Set<String> seenEventKeys = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public AgentScopeEventTranslator(String taskId, String attemptId, String leaseId,
            String correlationId, String runtime) {
        this.taskId = requireId(taskId, "taskId");
        this.attemptId = requireId(attemptId, "attemptId");
        this.leaseId = requireId(leaseId, "leaseId");
        this.correlationId = requireId(correlationId, "correlationId");
        this.runtime = requireId(runtime, "runtime");
    }

    private static String requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** Releases the attempt-scoped idempotency keys when the execution lifecycle ends. */
    public synchronized void clear() {
        seenEventKeys.clear();
        closed = true;
    }

    public synchronized AgentScopeExecutionEvent translate(AgentEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        String eventId = event.getId();
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("AgentScope event id must not be blank");
        }
        if (closed) {
            return lifecycleEvent(eventId, "translator closed");
        }
        Map<String, Object> metadata = event.getMetadata();
        if (metadata == null) {
            metadata = Map.of();
        }
        if (!metadata.containsKey("attemptId")
                || !attemptId.equals(String.valueOf(metadata.get("attemptId")))) {
            return lifecycleEvent(eventId, "stale attempt event");
        }
        boolean duplicate = !seenEventKeys.add(attemptId + "\u0000" + eventId);

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
                    "agent result", false, true, duplicate);
        }
        if (event instanceof AgentEndEvent) {
            return mapped(eventId, AgentScopeExecutionEvent.Kind.AGENT_ENDED,
                    "agent ended", true, true, duplicate);
        }
        if (event instanceof ExceedMaxItersEvent
                || event instanceof AllToolsDeniedEvent
                || event instanceof RequestStopEvent) {
            // 当前项目事件模型尚未区分 CANCELLED，停止请求保持 ERROR 终态。
            return mapped(eventId, AgentScopeExecutionEvent.Kind.ERROR,
                    "agent execution error", true, false, duplicate);
        }
        return mapped(eventId, AgentScopeExecutionEvent.Kind.UNMAPPED,
                "unmapped AgentScope event", false, true, duplicate);
    }

    /** Returns only a bounded, redacted text candidate from an AgentResultEvent. */
    public synchronized String safeResultCandidate(AgentEvent event) {
        if (!(event instanceof AgentResultEvent resultEvent) || resultEvent.getResult() == null) {
            return "";
        }
        return safeText(resultEvent.getResult().getTextContent());
    }

    private AgentScopeExecutionEvent mapped(String eventId, AgentScopeExecutionEvent.Kind kind,
            String safeMessage, boolean terminal, boolean success, boolean duplicate) {
        return new AgentScopeExecutionEvent(taskId, attemptId, leaseId, eventId, correlationId, runtime, kind,
                safeMessage,
                terminal, success, duplicate);
    }

    private AgentScopeExecutionEvent lifecycleEvent(String eventId, String message) {
        return new AgentScopeExecutionEvent(taskId, attemptId, leaseId, eventId, correlationId, runtime,
                AgentScopeExecutionEvent.Kind.STALE, message, false, false, false);
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
