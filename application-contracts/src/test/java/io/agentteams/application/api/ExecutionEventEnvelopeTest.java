package io.agentteams.application.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutionEventEnvelopeTest {

    private ExecutionEventPort.TaskEventReportCommand command(String payload) {
        return new ExecutionEventPort.TaskEventReportCommand(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Instant.now(), "worker-1", "tool.called", payload, 7, "corr-1");
    }

    @Test
    void taskEventEnvelopeCarriesOnlyTaskEventReport() {
        UUID taskId = UUID.randomUUID();
        ExecutionEventEnvelope envelope = ExecutionEventEnvelope.taskEvent(taskId, command("{\"tool\":\"web_search\"}"));
        assertEquals("TASK_EVENT", envelope.type());
        assertEquals(taskId, envelope.taskId());
        assertEquals("tool.called", envelope.taskEventReport().eventType());
        assertEquals(7, envelope.taskEventReport().sequence());
    }

    @Test
    void taskEventCommandRejectsOversizedPayload() {
        assertThrows(IllegalArgumentException.class, () -> command("x".repeat(4097)));
    }

    @Test
    void taskEventCommandRejectsBlankEventType() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEventPort.TaskEventReportCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                "worker-1", " ", "{}", 0, "corr-1"));
    }
}
