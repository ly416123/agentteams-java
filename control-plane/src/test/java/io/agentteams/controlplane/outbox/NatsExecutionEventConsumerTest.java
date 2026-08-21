package io.agentteams.controlplane.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.application.api.ExecutionEventPort;
import io.agentteams.domain.task.StaleTaskVersionException;
import io.nats.client.JetStream;
import io.nats.client.Message;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NatsExecutionEventConsumerTest {

    @Test
    void acknowledgesStaleExecutionEventsInsteadOfPoisoningTheConsumer() {
        Message message = mock(Message.class);
        when(message.getData()).thenReturn(taskEventJson().getBytes(StandardCharsets.UTF_8));
        ExecutionEventPort executionEvents = mock(ExecutionEventPort.class);
        doThrow(new StaleTaskVersionException(1, 2))
                .when(executionEvents).apply(any(), any(), any());
        NatsExecutionEventConsumer consumer = new NatsExecutionEventConsumer(
                mock(JetStream.class), executionEvents, new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules(), "test-consumer");

        consumer.process(message);

        verify(message).ack();
    }

    private static String taskEventJson() {
        return """
                {
                  "schemaVersion": 1,
                  "type": "TASK",
                  "taskId": "11111111-1111-1111-1111-111111111111",
                  "taskExecution": {
                    "eventId": "22222222-2222-2222-2222-222222222222",
                    "expectedVersion": 1,
                    "attemptId": "33333333-3333-3333-3333-333333333333",
                    "leaseId": "44444444-4444-4444-4444-444444444444",
                    "occurredAt": "2026-08-21T00:00:00Z",
                    "agentId": "55555555-5555-5555-5555-555555555555",
                    "source": "gateway",
                    "phase": "SUCCEEDED",
                    "failureCode": "",
                    "failureMessage": "",
                    "correlationId": "test"
                  },
                  "artifacts": []
                }
                """;
    }
}
