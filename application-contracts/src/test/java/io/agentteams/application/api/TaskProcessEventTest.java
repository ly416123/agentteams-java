package io.agentteams.application.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TaskProcessEventTest {
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-31T00:00:00Z");

    @Test
    void acceptsAnInlinePayload() {
        TaskProcessEvent event = new TaskProcessEvent(EVENT_ID, TASK_ID, RUN_ID, 3,
                "PROGRESS", TaskEventVisibility.REQUESTER, OCCURRED_AT, "request-1",
                "{\"percent\":25}", null);

        assertEquals(EVENT_ID, event.eventId());
        assertEquals(TASK_ID, event.taskId());
        assertEquals(RUN_ID, event.runId());
        assertEquals(3, event.sequence());
        assertEquals("PROGRESS", event.eventType());
        assertEquals(TaskEventVisibility.REQUESTER, event.visibility());
        assertEquals(OCCURRED_AT, event.occurredAt());
        assertEquals("request-1", event.correlationId());
        assertEquals("{\"percent\":25}", event.payload());
        assertEquals(null, event.payloadRef());
    }

    @Test
    void acceptsAReferenceInsteadOfAnInlinePayload() {
        TaskProcessEvent event = new TaskProcessEvent(EVENT_ID, TASK_ID, RUN_ID, 4,
                "RESULT", TaskEventVisibility.PROJECT_MEMBER, OCCURRED_AT, "request-1",
                null, "urn:agentteams:payload:result-4");

        assertEquals("urn:agentteams:payload:result-4", event.payloadRef());
        assertEquals(null, event.payload());
    }

    @Test
    void rejectsMissingOrDuplicatedPayloadRepresentations() {
        assertThrows(IllegalArgumentException.class, () -> event(0, "payload", "ref"));
        assertThrows(IllegalArgumentException.class, () -> event(0, null, null));
    }

    @Test
    void rejectsMissingRequiredFieldsNegativeSequenceAndInvalidVisibility() {
        assertThrows(NullPointerException.class, () -> new TaskProcessEvent(null, TASK_ID, RUN_ID, 0,
                "PROGRESS", TaskEventVisibility.REQUESTER, OCCURRED_AT, "request-1", "payload", null));
        assertThrows(NullPointerException.class, () -> new TaskProcessEvent(EVENT_ID, null, RUN_ID, 0,
                "PROGRESS", TaskEventVisibility.REQUESTER, OCCURRED_AT, "request-1", "payload", null));
        assertThrows(NullPointerException.class, () -> new TaskProcessEvent(EVENT_ID, TASK_ID, RUN_ID, 0,
                "PROGRESS", TaskEventVisibility.REQUESTER, null, "request-1", "payload", null));
        assertThrows(IllegalArgumentException.class, () -> new TaskProcessEvent(EVENT_ID, TASK_ID, RUN_ID, -1,
                "PROGRESS", TaskEventVisibility.REQUESTER, OCCURRED_AT, "request-1", "payload", null));
        assertThrows(NullPointerException.class, () -> new TaskProcessEvent(EVENT_ID, TASK_ID, RUN_ID, 0,
                "PROGRESS", (TaskEventVisibility) null, OCCURRED_AT, "request-1", "payload", null));
        assertThrows(IllegalArgumentException.class, () -> new TaskProcessEvent(EVENT_ID, TASK_ID, RUN_ID, 0,
                " ", TaskEventVisibility.REQUESTER, OCCURRED_AT, "request-1", "payload", null));
        assertThrows(IllegalArgumentException.class, () -> new TaskProcessEvent(EVENT_ID, TASK_ID, RUN_ID, 0,
                "PROGRESS", TaskEventVisibility.REQUESTER, OCCURRED_AT, " ", "payload", null));
    }

    @Test
    void rejectsAnUnknownVisibilityName() {
        assertThrows(IllegalArgumentException.class, () -> TaskEventVisibility.from("NOT_A_VISIBILITY"));
    }

    private static TaskProcessEvent event(long sequence, String payload, String payloadRef) {
        return new TaskProcessEvent(EVENT_ID, TASK_ID, RUN_ID, sequence, "PROGRESS",
                TaskEventVisibility.REQUESTER, OCCURRED_AT, "request-1", payload, payloadRef);
    }
}
