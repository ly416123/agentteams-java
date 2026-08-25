package io.agentteams.worker.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
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

    private final AgentScopeEventTranslator translator = new AgentScopeEventTranslator(TASK_ID, "attempt-a", LEASE_ID);

    @Test
    void translatesAgentStartEventWithoutLeakingAgentScopeType() {
        AgentScopeExecutionEvent result = translator.translate(
                new AgentStartEvent("agent-start-id", CREATED_AT, "session", "reply", "planner", "assistant"));

        assertThat(result.eventId()).isEqualTo("agent-start-id");
        assertThat(result.taskId()).isEqualTo(TASK_ID);
        assertThat(result.attemptId()).isEqualTo("attempt-a");
        assertThat(result.leaseId()).isEqualTo(LEASE_ID);
        assertThat(result.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.AGENT_STARTED);
        assertThat(result.safeMessage()).isEqualTo("agent started");
        assertThat(result.terminal()).isFalse();
        assertThat(result.success()).isTrue();
        assertThat(result.duplicate()).isFalse();
    }

    @Test
    void translatesModelCallStartAndEndEvents() {
        AgentScopeExecutionEvent started = translator.translate(
                new ModelCallStartEvent("model-start-id", CREATED_AT, "reply"));
        AgentScopeExecutionEvent completed = translator.translate(
                new ModelCallEndEvent("model-end-id", CREATED_AT, "reply", new ChatUsage(4, 6, 0.2)));

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
                new TextBlockDeltaEvent("text-id", CREATED_AT, "reply", "block", text));

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
                new TextBlockDeltaEvent("json-secret-id", CREATED_AT, "reply", "block", text));

        assertThat(result.safeMessage()).contains("[REDACTED]");
        assertThat(result.safeMessage()).doesNotContain("token-secret");
        assertThat(result.safeMessage()).doesNotContain("api-key-secret");
        assertThat(result.safeMessage()).doesNotContain("authorization-secret");
    }

    @Test
    void translatesToolCallStartAndEndWithoutToolArguments() {
        AgentScopeExecutionEvent started = translator.translate(
                new ToolCallStartEvent("tool-start-id", CREATED_AT, "reply", "call", "shell"
                        + "(command=do-not-copy-this-secret)"));
        AgentScopeExecutionEvent completed = translator.translate(
                new ToolCallEndEvent("tool-end-id", CREATED_AT, "reply", "call", "shell"));

        assertThat(started.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.TOOL_CALL_STARTED);
        assertThat(started.safeMessage()).isEqualTo("tool shell started");
        assertThat(started.safeMessage()).doesNotContain("do-not-copy-this-secret");
        assertThat(completed.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.TOOL_CALL_COMPLETED);
        assertThat(completed.safeMessage()).isEqualTo("tool shell completed");
    }

    @Test
    void translatesAgentResultBeforeAgentEndWithoutPrematureTerminalState() {
        AgentScopeExecutionEvent result = translator.translate(
                new AgentResultEvent("result-id", CREATED_AT, null));
        AgentScopeExecutionEvent ended = translator.translate(
                new AgentEndEvent("end-id", CREATED_AT, "reply"));

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
                new ExceedMaxItersEvent("error-id", CREATED_AT, "reply", 5, 5));

        assertThat(result.eventId()).isEqualTo("error-id");
        assertThat(result.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.ERROR);
        assertThat(result.safeMessage()).isEqualTo("agent execution error");
        assertThat(result.terminal()).isTrue();
        assertThat(result.success()).isFalse();
    }

    @Test
    void translatesAllToolsDeniedAsAnErrorTerminalEvent() {
        AgentScopeExecutionEvent result = translator.translate(new AllToolsDeniedEvent(List.of()));

        assertThat(result.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.ERROR);
        assertThat(result.terminal()).isTrue();
        assertThat(result.success()).isFalse();
    }

    @Test
    void translatesRequestStopAsAnErrorUntilCancelledIsASeparateProjectEvent() {
        // 当前项目事件模型尚未区分 CANCELLED，因此 RequestStop 保持 ERROR 终态。
        AgentScopeExecutionEvent result = translator.translate(new RequestStopEvent("user requested stop"));

        assertThat(result.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.ERROR);
        assertThat(result.terminal()).isTrue();
        assertThat(result.success()).isFalse();
    }

    @Test
    void mapsUnknownEventToUnmappedWithoutBlockingTerminalState() {
        AgentScopeExecutionEvent result = translator.translate(
                new CustomEvent("unknown-id", CREATED_AT, "future-event", Map.of("secret", "do-not-copy")));

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
        assertThat(result.eventId()).isEqualTo("stale-id");
        assertThat(result.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.STALE);
        assertThat(result.safeMessage()).isEqualTo("stale attempt event");
        assertThat(result.terminal()).isFalse();
        assertThat(result.success()).isFalse();
        assertThat(result.duplicate()).isFalse();
    }

    @Test
    void scopesIdempotencyByAttemptAndReleasesSeenEventIdsOnClear() {
        AgentStartEvent event = new AgentStartEvent("same-id", CREATED_AT, "session", "reply", "agent", "assistant");

        AgentScopeExecutionEvent first = translator.translate(event);
        AgentScopeExecutionEvent second = translator.translate(event);
        AgentScopeExecutionEvent otherAttempt = new AgentScopeEventTranslator(TASK_ID, "attempt-b", LEASE_ID)
                .translate(event);

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
        assertThat(closed.terminal()).isFalse();
        assertThat(closed.success()).isFalse();
        assertThat(closed.duplicate()).isFalse();
    }

    @Test
    void requiresAllExecutionIdsForTheIdempotencyBoundary() {
        assertThatThrownBy(() -> new AgentScopeEventTranslator("  ", "attempt-a", LEASE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("taskId must not be blank");
        assertThatThrownBy(() -> new AgentScopeEventTranslator(TASK_ID, "  ", LEASE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("attemptId must not be blank");
        assertThatThrownBy(() -> new AgentScopeEventTranslator(TASK_ID, "attempt-a", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("leaseId must not be blank");
    }
}
