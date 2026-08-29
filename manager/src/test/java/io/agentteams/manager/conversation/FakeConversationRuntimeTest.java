package io.agentteams.manager.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakeConversationRuntimeTest {
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final ConversationRuntimePort.Context CONTEXT = new ConversationRuntimePort.Context(
            "project-a", "team-a", "worker-a", "task-a", SESSION_ID);

    @Test
    void emitsDeterministicStartedDeltaAndCompletedEvents() {
        FakeConversationRuntime runtime = new FakeConversationRuntime();
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));

        assertThat(runtime.events(SESSION_ID, 0)).extracting(ConversationEvent::type)
                .containsExactly("conversation.started", "message.delta", "message.completed");
        assertThat(runtime.events(SESSION_ID, 0)).extracting(ConversationEvent::cursor)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void cancellationAppendsOneTerminalEventAndSuppressesLaterMessages() {
        FakeConversationRuntime runtime = new FakeConversationRuntime();
        runtime.start(CONTEXT);
        runtime.cancel(SESSION_ID);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "ignored"));
        runtime.cancel(SESSION_ID);

        assertThat(runtime.events(SESSION_ID, 0)).extracting(ConversationEvent::type)
                .containsExactly("conversation.started", "conversation.cancelled");
    }

    @Test
    void unavailableWorkerIsReportedAsAStableRuntimeCategory() {
        FakeConversationRuntime runtime = new FakeConversationRuntime(false);

        assertThatThrownBy(() -> runtime.start(CONTEXT))
                .isInstanceOf(ConversationRuntimeException.class)
                .satisfies(error -> assertThat(((ConversationRuntimeException) error).code())
                        .isEqualTo(ConversationRuntimeException.Code.WORKER_UNAVAILABLE));
        assertThat(runtime.events(SESSION_ID, 0)).isEmpty();
    }

    @Test
    void replaysOnlyEventsAfterTheRequestedCursor() {
        FakeConversationRuntime runtime = new FakeConversationRuntime();
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));

        assertThat(runtime.events(SESSION_ID, 1)).extracting(ConversationEvent::cursor)
                .containsExactly(2L, 3L);
    }
}
