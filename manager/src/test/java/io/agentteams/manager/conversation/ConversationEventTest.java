package io.agentteams.manager.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationEventTest {
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void acceptsOnlyTheFixedConversationEventTypes() {
        for (String type : new String[] {
                "conversation.started", "message.delta", "message.completed", "task.updated",
                "tool.started", "tool.completed", "conversation.cancelled", "conversation.failed" }) {
            assertThat(ConversationEvent.of(SESSION_ID, 1, type, "{}", Instant.EPOCH).type())
                    .isEqualTo(type);
        }

        assertThatThrownBy(() -> ConversationEvent.of(1, "unknown", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event type");
    }

    @Test
    void rejectsNonPositiveCursors() {
        assertThatThrownBy(() -> ConversationEvent.of(SESSION_ID, 0,
                "conversation.started", "{}", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor");
    }

    @Test
    void eventCursorIsMonotonicWhenEventsAreCreatedForOneSession() {
        ConversationEvent first = ConversationEvent.of(SESSION_ID, 1,
                "conversation.started", "{}", Instant.EPOCH);
        ConversationEvent second = first.next("message.delta", "{\"text\":\"hi\"}");
        ConversationEvent third = second.next("message.completed", "{\"text\":\"hi\"}");

        assertThat(second.cursor()).isEqualTo(first.cursor() + 1);
        assertThat(third.cursor()).isEqualTo(second.cursor() + 1);
        assertThat(third.sessionId()).isEqualTo(SESSION_ID);
    }

    @Test
    void runtimeContextContainsOnlyConversationResourceReferences() {
        ConversationRuntimePort.Context context = new ConversationRuntimePort.Context(
                "project-a", "team-a", "worker-a", "task-a", SESSION_ID);

        assertThat(context.project()).isEqualTo("project-a");
        assertThat(context.team()).isEqualTo("team-a");
        assertThat(context.worker()).isEqualTo("worker-a");
        assertThat(context.task()).isEqualTo("task-a");
        assertThat(context.sessionId()).isEqualTo(SESSION_ID);
        assertThat(ConversationRuntimePort.Context.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("project", "team", "worker", "task", "sessionId");
    }
}
