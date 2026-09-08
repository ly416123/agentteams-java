package io.agentteams.application.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
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

    @Test
    void taskEventCommandRejectsOutOfRangeSequence() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEventPort.TaskEventReportCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                "worker-1", "tool.called", "{}", -1, "corr-1"));
        // proto 字段为 uint32，超出取值域一律拒绝。
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEventPort.TaskEventReportCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                "worker-1", "tool.called", "{}", 0x100000000L, "corr-1"));
    }

    @Test
    void taskEventCommandAcceptsNullPayloadAndRejectsMultiByteOverflow() {
        // null 载荷合法（proto bytes 允许为空）。
        assertNotNull(command(null));
        // 4095 字节的多字节字符载荷合法：上限按 UTF-8 字节而非字符数计。
        assertNotNull(command("日".repeat(1365)));
        // 4098 字节超限。
        assertThrows(IllegalArgumentException.class, () -> command("日".repeat(1366)));
    }

    @Test
    void taskEventCommandDefaultsCorrelationId() {
        ExecutionEventPort.TaskEventReportCommand fallback = new ExecutionEventPort.TaskEventReportCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                "worker-1", "tool.called", "{}", 0, null);
        assertEquals("unknown", fallback.correlationId());
    }

    @Test
    void taskEventEnvelopeRejectsReportWithoutType() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEventEnvelope(
                1, "TASK_EVENT", UUID.randomUUID(), null, null, null, null, List.of(), "corr-1", "", ""));
    }
}
