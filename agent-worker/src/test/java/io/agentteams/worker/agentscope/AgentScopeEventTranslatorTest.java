package io.agentteams.worker.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.model.ChatUsage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentScopeEventTranslatorTest {

    private static final String CREATED_AT = "2026-08-25T00:00:00Z";
    private static final String TASK_ID = "task-a";
    private static final String LEASE_ID = "lease-a";
    private static final String CORRELATION_ID = "corr-1";
    private static final String RUNTIME = "AGENTSCOPE";

    private final AgentScopeEventTranslator translator = new AgentScopeEventTranslator(
            TASK_ID, "attempt-a", LEASE_ID, CORRELATION_ID, RUNTIME);

    @Test
    void translatesAgentStartEventWithoutLeakingAgentScopeType() {
        AgentScopeExecutionEvent result = translator.translate(
                withAttempt(new AgentStartEvent("agent-start-id", CREATED_AT, "session", "reply", "planner", "assistant")));

        assertThat(result.eventId()).isEqualTo("agent-start-id");
        assertThat(result.taskId()).isEqualTo(TASK_ID);
        assertThat(result.attemptId()).isEqualTo("attempt-a");
        assertThat(result.leaseId()).isEqualTo(LEASE_ID);
        assertThat(result.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(result.runtime()).isEqualTo(RUNTIME);
        assertThat(result.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.AGENT_STARTED);
        assertThat(result.safeMessage()).isEqualTo("agent started");
        assertThat(result.terminal()).isFalse();
        assertThat(result.success()).isTrue();
        assertThat(result.duplicate()).isFalse();
    }

    @Test
    void translatesModelCallStartAndEndEvents() {
        AgentScopeExecutionEvent started = translator.translate(
                withAttempt(new ModelCallStartEvent("model-start-id", CREATED_AT, "reply")));
        AgentScopeExecutionEvent completed = translator.translate(
                withAttempt(new ModelCallEndEvent("model-end-id", CREATED_AT, "reply", new ChatUsage(4, 6, 0.2))));

        assertThat(started.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.MODEL_CALL_STARTED);
        assertThat(started.terminal()).isFalse();
        assertThat(completed.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.MODEL_CALL_COMPLETED);
        assertThat(completed.safeMessage()).isEqualTo("model call completed");
        assertThat(completed.success()).isTrue();
    }

    @Test
    void translatesTextDeltaWithRedactionAndLengthLimit() {
        String text = "answer Authorization: Bearer sk-secret-token-1234567890 "
                + "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";

        AgentScopeExecutionEvent result = translator.translate(
                withAttempt(new TextBlockDeltaEvent("text-id", CREATED_AT, "reply", "block", text)));

        assertThat(result.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.TEXT_DELTA);
        assertThat(result.safeMessage()).hasSizeLessThanOrEqualTo(128);
        assertThat(result.safeMessage()).doesNotContain("sk-secret-token-1234567890");
        assertThat(result.safeMessage()).doesNotContain("Authorization");
    }

    @Test
    void redactsJsonQuotedTokenApiKeyAndAuthorizationValues() {
        String text = "{\"token\":\"token-secret\",\"apiKey\":\"api-key-secret\","
                + "\"Authorization\":\"Bearer authorization-secret\"}";

        AgentScopeExecutionEvent result = translator.translate(
                withAttempt(new TextBlockDeltaEvent("json-secret-id", CREATED_AT, "reply", "block", text)));

        assertThat(result.safeMessage()).contains("[REDACTED]");
        assertThat(result.safeMessage()).doesNotContain("token-secret");
        assertThat(result.safeMessage()).doesNotContain("api-key-secret");
        assertThat(result.safeMessage()).doesNotContain("authorization-secret");
    }

    @Test
    void translatesToolCallStartAndEndWithoutToolArguments() {
        AgentScopeExecutionEvent started = translator.translate(
                withAttempt(new ToolCallStartEvent("tool-start-id", CREATED_AT, "reply", "call", "shell"
                        + "(command=do-not-copy-this-secret)")));
        AgentScopeExecutionEvent completed = translator.translate(
                withAttempt(new ToolCallEndEvent("tool-end-id", CREATED_AT, "reply", "call", "shell")));

        assertThat(started.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.TOOL_CALL_STARTED);
        assertThat(started.safeMessage()).isEqualTo("tool shell started");
        assertThat(started.safeMessage()).doesNotContain("do-not-copy-this-secret");
        assertThat(completed.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.TOOL_CALL_COMPLETED);
        assertThat(completed.safeMessage()).isEqualTo("tool shell completed");
    }

    @Test
    void translatesAgentResultBeforeAgentEndWithoutPrematureTerminalState() {
        AgentScopeExecutionEvent result = translator.translate(
                withAttempt(new AgentResultEvent("result-id", CREATED_AT, null)));
        AgentScopeExecutionEvent ended = translator.translate(
                withAttempt(new AgentEndEvent("end-id", CREATED_AT, "reply")));

        assertThat(result.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.AGENT_RESULT);
        assertThat(result.terminal()).isFalse();
        assertThat(result.success()).isTrue();
        assertThat(ended.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.AGENT_ENDED);
        assertThat(ended.terminal()).isTrue();
        assertThat(ended.success()).isTrue();
    }

    @Test
    void translatesAgentScopeErrorAsFailedTerminalEventWithoutCopyingDetails() {
        AgentScopeExecutionEvent result = translator.translate(
                withAttempt(new ExceedMaxItersEvent("error-id", CREATED_AT, "reply", 5, 5)));

        assertThat(result.eventId()).isEqualTo("error-id");
        assertThat(result.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.ERROR);
        assertThat(result.safeMessage()).isEqualTo("agent execution error");
        assertThat(result.terminal()).isTrue();
        assertThat(result.success()).isFalse();
    }

    @Test
    void translatesAllToolsDeniedAsAnErrorTerminalEvent() {
        AgentScopeExecutionEvent result = translator.translate(withAttempt(new AllToolsDeniedEvent(List.of())));

        assertThat(result.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.ERROR);
        assertThat(result.terminal()).isTrue();
        assertThat(result.success()).isFalse();
    }

    @Test
    void translatesRequestStopAsAnErrorUntilCancelledIsASeparateProjectEvent() {
        // 当前项目事件模型尚未区分 CANCELLED，因此 RequestStop 保持 ERROR 终态。
        AgentScopeExecutionEvent result = translator.translate(withAttempt(new RequestStopEvent("user requested stop")));

        assertThat(result.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.ERROR);
        assertThat(result.terminal()).isTrue();
        assertThat(result.success()).isFalse();
    }

    @Test
    void mapsUnknownEventToUnmappedWithoutBlockingTerminalState() {
        AgentScopeExecutionEvent result = translator.translate(
                withAttempt(new CustomEvent("unknown-id", CREATED_AT, "future-event", Map.of("secret", "do-not-copy"))));

        assertThat(result.eventId()).isEqualTo("unknown-id");
        assertThat(result.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.UNMAPPED);
        assertThat(result.safeMessage()).isEqualTo("unmapped AgentScope event");
        assertThat(result.terminal()).isFalse();
        assertThat(result.success()).isTrue();
        assertThat(result.safeMessage()).doesNotContain("do-not-copy");
    }

    @Test
    void rejectsEventFromAnotherAttemptAsStaleWithoutMarkingItCurrent() {
        AgentScopeExecutionEvent result = translator.translate(new AgentStartEvent(
                "stale-id", CREATED_AT, "session", "reply", "agent", "assistant")
                .withMetadata(Map.of("attemptId", "attempt-other")));

        assertThat(result.taskId()).isEqualTo(TASK_ID);
        assertThat(result.attemptId()).isEqualTo("attempt-a");
        assertThat(result.leaseId()).isEqualTo(LEASE_ID);
        assertThat(result.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(result.runtime()).isEqualTo(RUNTIME);
        assertThat(result.eventId()).isEqualTo("stale-id");
        assertThat(result.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.STALE);
        assertThat(result.safeMessage()).isEqualTo("stale execution context");
        assertThat(result.terminal()).isFalse();
        assertThat(result.success()).isFalse();
        assertThat(result.duplicate()).isFalse();
    }

    @Test
    void rejectsEventWithoutAttemptContextAsStale() {
        AgentScopeExecutionEvent result = translator.translate(
                new AgentStartEvent("missing-attempt-id", CREATED_AT, "session", "reply", "agent", "assistant"));

        assertThat(result.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.STALE);
        assertThat(result.safeMessage()).isEqualTo("stale execution context");
        assertThat(result.terminal()).isFalse();
        assertThat(result.success()).isFalse();
        assertThat(result.duplicate()).isFalse();
    }

    @Test
    void rejectsEventFromAnotherLeaseEvenWhenAttemptMatches() {
        AgentScopeExecutionEvent result = translator.translate(new AgentStartEvent(
                "stale-lease-id", CREATED_AT, "session", "reply", "agent", "assistant")
                .withMetadata(Map.of("attemptId", "attempt-a", "leaseId", "lease-old")));

        assertThat(result.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.STALE);
        assertThat(result.safeMessage()).isEqualTo("stale execution context");
        assertThat(result.terminal()).isFalse();
        assertThat(result.success()).isFalse();
    }

    @Test
    void staleEventsAreIdempotentBySuppliedAttemptAndEventId() {
        AgentEvent event = new AgentStartEvent(
                "same-stale-id", CREATED_AT, "session", "reply", "agent", "assistant")
                .withMetadata(Map.of("attemptId", "attempt-old", "leaseId", "lease-old"));

        AgentScopeExecutionEvent first = translator.translate(event);
        AgentScopeExecutionEvent second = translator.translate(event);

        assertThat(first.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.STALE);
        assertThat(first.duplicate()).isFalse();
        assertThat(second.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.STALE);
        assertThat(second.duplicate()).isTrue();
    }

    @Test
    void scopesIdempotencyByAttemptAndReleasesSeenEventIdsOnClear() {
        AgentEvent event = withAttempt(
                new AgentStartEvent("same-id", CREATED_AT, "session", "reply", "agent", "assistant"));

        AgentScopeExecutionEvent first = translator.translate(event);
        AgentScopeExecutionEvent second = translator.translate(event);
        AgentScopeExecutionEvent otherAttempt = new AgentScopeEventTranslator(
                TASK_ID, "attempt-b", LEASE_ID, CORRELATION_ID, RUNTIME)
                .translate(eventWithAttempt(
                        new AgentStartEvent("same-id", CREATED_AT, "session", "reply", "agent", "assistant"),
                        "attempt-b"));

        assertThat(first.attemptId()).isEqualTo("attempt-a");
        assertThat(first.duplicate()).isFalse();
        assertThat(second.eventId()).isEqualTo("same-id");
        assertThat(second.duplicate()).isTrue();
        assertThat(otherAttempt.attemptId()).isEqualTo("attempt-b");
        assertThat(otherAttempt.duplicate()).isFalse();

        translator.clear();
        AgentScopeExecutionEvent closed = translator.translate(event);
        assertThat(closed.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.STALE);
        assertThat(closed.safeMessage()).isEqualTo("translator closed");
        assertThat(closed.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(closed.runtime()).isEqualTo(RUNTIME);
        assertThat(closed.terminal()).isFalse();
        assertThat(closed.success()).isFalse();
        assertThat(closed.duplicate()).isFalse();
    }

    @Test
    void requiresAllExecutionIdsForTheIdempotencyBoundary() {
        assertThatThrownBy(() -> new AgentScopeEventTranslator(
                "  ", "attempt-a", LEASE_ID, CORRELATION_ID, RUNTIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("taskId must not be blank");
        assertThatThrownBy(() -> new AgentScopeEventTranslator(
                TASK_ID, "  ", LEASE_ID, CORRELATION_ID, RUNTIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("attemptId must not be blank");
        assertThatThrownBy(() -> new AgentScopeEventTranslator(
                TASK_ID, "attempt-a", "  ", CORRELATION_ID, RUNTIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("leaseId must not be blank");
        assertThatThrownBy(() -> new AgentScopeEventTranslator(
                TASK_ID, "attempt-a", LEASE_ID, "  ", RUNTIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("correlationId must not be blank");
        assertThatThrownBy(() -> new AgentScopeEventTranslator(
                TASK_ID, "attempt-a", LEASE_ID, CORRELATION_ID, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("runtime must not be blank");
    }

    private static AgentEvent withAttempt(AgentEvent event) {
        return eventWithAttempt(event, "attempt-a");
    }

    private static AgentEvent eventWithAttempt(AgentEvent event, String attemptId) {
        return event.withMetadataEntry("attemptId", attemptId)
                .withMetadataEntry("leaseId", LEASE_ID);
    }
}
