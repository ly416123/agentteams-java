package io.agentteams.controlplane.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.application.api.ExecutionEventPort;
import io.agentteams.domain.task.StaleTaskVersionException;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.JetStream;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.impl.NatsJetStreamMetaData;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
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

    @Test
    void redeliversExecutionEventsThatAreAheadOfTheCurrentAggregateVersion() {
        Message message = mock(Message.class);
        when(message.getData()).thenReturn(taskEventJson().getBytes(StandardCharsets.UTF_8));
        ExecutionEventPort executionEvents = mock(ExecutionEventPort.class);
        doThrow(new StaleTaskVersionException(5, 4))
                .when(executionEvents).apply(any(), any(), any());
        NatsExecutionEventConsumer consumer = new NatsExecutionEventConsumer(
                mock(JetStream.class), executionEvents, new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules(), "test-consumer");

        consumer.process(message);

        verify(message).nakWithDelay(Duration.ofMillis(250));
        verify(message, never()).ack();
    }

    @Test
    void backsOffOutOfOrderRedeliveryAfterRepeatedDeliveryAttempts() {
        Message message = mock(Message.class);
        when(message.getData()).thenReturn(taskEventJson().getBytes(StandardCharsets.UTF_8));
        NatsJetStreamMetaData metadata = mock(NatsJetStreamMetaData.class);
        when(message.metaData()).thenReturn(metadata);
        when(metadata.deliveredCount()).thenReturn(4L);
        ExecutionEventPort executionEvents = mock(ExecutionEventPort.class);
        doThrow(new StaleTaskVersionException(5, 4))
                .when(executionEvents).apply(any(), any(), any());
        NatsExecutionEventConsumer consumer = new NatsExecutionEventConsumer(
                mock(JetStream.class), executionEvents, new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules(), "test-consumer");

        consumer.process(message);

        verify(message).nakWithDelay(Duration.ofSeconds(2));
        verify(message, never()).ack();
    }

    @Test
    void restoresTraceContextBeforeApplyingExecutionEvent() {
        Message message = mock(Message.class);
        when(message.getData()).thenReturn(taskEventJson().replace("\"artifacts\": []",
                "\"traceparent\":\"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\","
                        + "\"tracestate\":\"vendor=value\",\"artifacts\": []")
                .getBytes(StandardCharsets.UTF_8));
        ExecutionEventPort executionEvents = mock(ExecutionEventPort.class);
        NatsExecutionEventConsumer consumer = new NatsExecutionEventConsumer(
                mock(JetStream.class), executionEvents, new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules(), "test-consumer");

        consumer.process(message);

        var command = org.mockito.ArgumentCaptor.forClass(ExecutionEventPort.TaskExecutionCommand.class);
        verify(executionEvents).apply(any(), command.capture(), any());
        assertThat(command.getValue().traceparent())
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(command.getValue().tracestate()).isEqualTo("vendor=value");
    }

    @Test
    void rebuildsDurableSubscriptionAfterNatsResubscribedEvent() throws Exception {
        Connection connection = mock(Connection.class);
        JetStream jetStream = mock(JetStream.class);
        JetStreamSubscription firstSubscription = mock(JetStreamSubscription.class);
        JetStreamSubscription secondSubscription = mock(JetStreamSubscription.class);
        CountDownLatch consumerStopped = new CountDownLatch(1);
        when(connection.jetStream()).thenReturn(jetStream);
        when(jetStream.subscribe(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(PushSubscribeOptions.class)))
                .thenReturn(firstSubscription, secondSubscription);
        when(firstSubscription.nextMessage(org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenAnswer(invocation -> { consumerStopped.await(); return null; });
        when(secondSubscription.nextMessage(org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenAnswer(invocation -> { consumerStopped.await(); return null; });

        NatsExecutionEventConsumer consumer = new NatsExecutionEventConsumer(connection,
                mock(ExecutionEventPort.class), command -> { }, new com.fasterxml.jackson.databind.ObjectMapper(),
                "control-plane-execution-events", io.agentteams.controlplane.observability.AsyncConsumerTracing.noop());
        consumer.start();

        var listener = org.mockito.ArgumentCaptor.forClass(ConnectionListener.class);
        verify(connection).addConnectionListener(listener.capture());
        listener.getValue().connectionEvent(connection, ConnectionListener.Events.RESUBSCRIBED);

        verify(firstSubscription).unsubscribe();
        verify(jetStream, org.mockito.Mockito.times(2)).subscribe(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(PushSubscribeOptions.class));
        consumer.close();
        consumerStopped.countDown();
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
