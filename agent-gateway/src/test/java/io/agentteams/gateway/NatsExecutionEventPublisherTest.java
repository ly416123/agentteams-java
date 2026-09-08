package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.ExecutionEventEnvelope;
import io.agentteams.application.api.ExecutionEventPort.TaskEventReportCommand;
import io.nats.client.JetStream;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NatsExecutionEventPublisherTest {

    @Test
    void publishesTaskEventEnvelopeToExecutionSubject() throws Exception {
        JetStream jetStream = mock(JetStream.class);
        when(jetStream.publish(anyString(), any(byte[].class))).thenReturn(null);
        // occurredAt 是 Instant，序列化/反序列化两端都需要 JSR310 模块。
        NatsExecutionEventPublisher publisher = new NatsExecutionEventPublisher(jetStream,
                new ObjectMapper().findAndRegisterModules());
        UUID taskId = UUID.randomUUID();

        publisher.taskEventReport(taskId, new TaskEventReportCommand(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Instant.now(), "worker-1", "tool.called", "{\"tool\":\"web_search\"}",
                1, "corr-1"));

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(jetStream).publish(anyString(), body.capture());
        ExecutionEventEnvelope envelope = new ObjectMapper().findAndRegisterModules()
                .readValue(body.getValue(), ExecutionEventEnvelope.class);
        assertThat(envelope.type()).isEqualTo("TASK_EVENT");
        assertThat(envelope.taskId()).isEqualTo(taskId);
        assertThat(envelope.taskEventReport().eventType()).isEqualTo("tool.called");
    }
}
