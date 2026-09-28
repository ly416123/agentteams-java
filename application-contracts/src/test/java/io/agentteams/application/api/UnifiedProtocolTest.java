package io.agentteams.application.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UnifiedProtocolTest {
    private static final UUID COMMAND_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EVENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CONVERSATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TASK_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-23T10:00:00Z");
    private static final CommandActor ACTOR = new CommandActor("USER", "user-1", "CONSOLE");

    @Test
    void commandEnvelopeRequiresStableCommandIdentityAndContext() {
        CommandEnvelope command = command();

        assertThat(command.commandId()).isEqualTo(COMMAND_ID);
        assertThat(command.commandType()).isEqualTo("task.start");
        assertThat(command.tenantId()).isEqualTo("tenant-1");
        assertThat(command.actor()).isEqualTo(ACTOR);
        assertThat(command.aggregateVersion()).isEqualTo(4L);
        assertThat(command.occurredAt()).isEqualTo(OCCURRED_AT);

        assertThatThrownBy(() -> new CommandEnvelope(
                COMMAND_ID, "", "tenant-1", ACTOR, null, TASK_ID, "TASK", 4L,
                JsonNodeFactory.instance.objectNode(), "key", "trace", OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandEnvelope(
                COMMAND_ID, "task.start", "", ACTOR, null, TASK_ID, "TASK", 4L,
                JsonNodeFactory.instance.objectNode(), "key", "trace", OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandEnvelope(
                COMMAND_ID, "task.start", "tenant-1", ACTOR, null, TASK_ID, "TASK", -1L,
                JsonNodeFactory.instance.objectNode(), "key", "trace", OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandEnvelope(
                COMMAND_ID, "task.start", "tenant-1", ACTOR, null, TASK_ID, "TASK", 4L,
                JsonNodeFactory.instance.objectNode(), "", "trace", OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandEnvelope(
                COMMAND_ID, "task.start", "tenant-1", ACTOR, null, TASK_ID, "TASK", 4L,
                JsonNodeFactory.instance.objectNode(), "key", "trace", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void actorAndEventEnvelopeRejectInvalidIdentityAndVersions() {
        assertThatThrownBy(() -> new CommandActor("", "user-1", "CONSOLE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandActor("USER", "", "CONSOLE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandActor("USER", "user-1", ""))
                .isInstanceOf(IllegalArgumentException.class);

        UnifiedEventEnvelope event = new UnifiedEventEnvelope(
                EVENT_ID, "task.started", 1, "tenant-1", "TASK", TASK_ID, 5L,
                CONVERSATION_ID, TASK_ID, OCCURRED_AT, ACTOR,
                JsonNodeFactory.instance.objectNode(), "TEAM", "trace-1");
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.aggregateVersion()).isEqualTo(5L);

        assertThatThrownBy(() -> new UnifiedEventEnvelope(
                EVENT_ID, "task.started", 0, "tenant-1", "TASK", TASK_ID, 5L,
                CONVERSATION_ID, TASK_ID, OCCURRED_AT, ACTOR,
                JsonNodeFactory.instance.objectNode(), "TEAM", "trace-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UnifiedEventEnvelope(
                EVENT_ID, "task.started", 1, "tenant-1", "TASK", TASK_ID, -1L,
                CONVERSATION_ID, TASK_ID, OCCURRED_AT, ACTOR,
                JsonNodeFactory.instance.objectNode(), "TEAM", "trace-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commandResultPreservesDuplicateStatus() {
        CommandResult result = new CommandResult(COMMAND_ID, CommandStatus.DUPLICATE, true,
                TASK_ID, 5L, "trace-1");

        assertThat(result.status()).isEqualTo(CommandStatus.DUPLICATE);
        assertThat(result.deduplicated()).isTrue();
        assertThat(result.taskId()).isEqualTo(TASK_ID);
    }

    @Test
    void publicProtocolUsesExplicitJsonFieldNamesAndEnumValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String json = mapper.writeValueAsString(command());
        String resultJson = mapper.writeValueAsString(new CommandResult(
                COMMAND_ID, CommandStatus.ACCEPTED, false, TASK_ID, 4L, "trace-1"));

        assertThat(json).contains("\"commandId\"")
                .contains("\"commandType\":\"task.start\"")
                .contains("\"tenantId\":\"tenant-1\"")
                .contains("\"idempotencyKey\":\"tenant-1:req-1\"")
                .contains("\"occurredAt\":\"2026-09-23T10:00:00Z\"")
                .contains("\"type\":\"USER\"");
        assertThat(resultJson).contains("\"status\":\"ACCEPTED\"")
                .contains("\"deduplicated\":false");
    }

    @Test
    void timelineItemRequiresStableIdentityAndSequence() {
        TimelineItem item = new TimelineItem(
                "message-1", "CONVERSATION_MESSAGE", CONVERSATION_ID, TASK_ID,
                "message.completed", "user-1", OCCURRED_AT, 2L,
                "TEAM", null, "done", 3L);

        assertThat(item.itemId()).isEqualTo("message-1");
        assertThat(item.sequence()).isEqualTo(2L);
        assertThatThrownBy(() -> new TimelineItem(
                "", "CONVERSATION_MESSAGE", CONVERSATION_ID, TASK_ID,
                "message.completed", "user-1", OCCURRED_AT, 2L,
                "TEAM", null, "done", 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimelineItem(
                "message-1", "CONVERSATION_MESSAGE", CONVERSATION_ID, TASK_ID,
                "message.completed", "user-1", OCCURRED_AT, -1L,
                "TEAM", null, "done", 3L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CommandEnvelope command() {
        return new CommandEnvelope(COMMAND_ID, "task.start", "tenant-1", ACTOR,
                CONVERSATION_ID, TASK_ID, "TASK", 4L,
                JsonNodeFactory.instance.objectNode().put("priority", "normal"),
                "tenant-1:req-1", "trace-1", OCCURRED_AT);
    }
}
