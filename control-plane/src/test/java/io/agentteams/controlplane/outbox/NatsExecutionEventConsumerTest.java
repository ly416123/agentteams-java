package io.agentteams.controlplane.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.application.api.ConfigEventPort;
import io.agentteams.application.api.ExecutionEventPort;
import io.agentteams.domain.task.StaleTaskVersionException;
import io.agentteams.domain.task.IllegalTaskTransitionException;
import io.agentteams.domain.task.TaskPhase;
import io.agentteams.observability.AsyncConsumerTracing;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    void acknowledgesImpossibleTerminalTransitionInsteadOfPoisoningTheConsumer() {
        Message message = mock(Message.class);
        when(message.getData()).thenReturn(taskEventJson().getBytes(StandardCharsets.UTF_8));
        ExecutionEventPort executionEvents = mock(ExecutionEventPort.class);
        doThrow(new IllegalTaskTransitionException(TaskPhase.CANCELLED, TaskPhase.RUNNING))
                .when(executionEvents).apply(any(), any(), any());
        NatsExecutionEventConsumer consumer = new NatsExecutionEventConsumer(
                mock(JetStream.class), executionEvents, new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules(), "test-consumer");

        consumer.process(message);

        verify(message).ack();
    }

    @Test
    void acknowledgesUnauthorizedExecutionEventsInsteadOfRedeliveringThem() {
        Message message = mock(Message.class);
        when(message.getData()).thenReturn(taskEventJson().getBytes(StandardCharsets.UTF_8));
        ExecutionEventPort executionEvents = mock(ExecutionEventPort.class);
        doThrow(new io.agentteams.controlplane.security.AuthorizationException("agent mismatch"))
                .when(executionEvents).apply(any(), any(), any());
        NatsExecutionEventConsumer consumer = new NatsExecutionEventConsumer(
                mock(JetStream.class), executionEvents, new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules(), "test-consumer");

        consumer.process(message);

        verify(message).ack();
        verify(message, never()).nakWithDelay(any(Duration.class));
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
    void acknowledgesConfigAppliedEventsWithoutWaitingBehindExecutionBacklog() {
        Message message = mock(Message.class);
        when(message.getData()).thenReturn("""
                {
                  "schemaVersion": 1,
                  "type": "CONFIG_APPLIED",
                  "eventId": "11111111-1111-1111-1111-111111111111",
                  "bindingId": "22222222-2222-2222-2222-222222222222",
                  "snapshotId": "33333333-3333-3333-3333-333333333333",
                  "agentId": "44444444-4444-4444-4444-444444444444",
                  "configVersion": 2,
                  "applied": true,
                  "errorMessage": "",
                  "occurredAt": "2026-08-21T00:00:00Z",
                  "source": "gateway",
                  "correlationId": "config-test"
                }
                """.getBytes(StandardCharsets.UTF_8));
        ConfigEventPort configEvents = mock(ConfigEventPort.class);
        NatsExecutionEventConsumer consumer = new NatsExecutionEventConsumer(
                mock(JetStream.class), mock(ExecutionEventPort.class), configEvents,
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), "test-consumer");

        consumer.process(message);

        verify(configEvents).applied(any(ConfigEventPort.ConfigAppliedCommand.class));
        verify(message).ack();
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
                "control-plane-execution-events", io.agentteams.observability.AsyncConsumerTracing.noop());
        consumer.start();

        var listener = org.mockito.ArgumentCaptor.forClass(ConnectionListener.class);
        verify(connection).addConnectionListener(listener.capture());
        listener.getValue().connectionEvent(connection, ConnectionListener.Events.RESUBSCRIBED);

        verify(firstSubscription).unsubscribe();
        var options = org.mockito.ArgumentCaptor.forClass(PushSubscribeOptions.class);
        verify(jetStream, org.mockito.Mockito.times(2)).subscribe(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), options.capture());
        assertThat(options.getAllValues()).allMatch(option ->
                option.getConsumerConfiguration().getMaxAckPending() >= 8);
        consumer.close();
        consumerStopped.countDown();
    }

    @Test
    void keepsTheExistingSubscriptionWhenReconnectCreationFailsThenRecovers() throws Exception {
        Connection connection = mock(Connection.class);
        JetStream jetStream = mock(JetStream.class);
        JetStreamSubscription first = mock(JetStreamSubscription.class);
        JetStreamSubscription recovered = mock(JetStreamSubscription.class);
        CountDownLatch consumerStopped = new CountDownLatch(1);
        when(connection.jetStream()).thenReturn(jetStream);
        when(jetStream.subscribe(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(PushSubscribeOptions.class)))
                .thenReturn(first)
                .thenThrow(new java.io.IOException("reconnect unavailable"))
                .thenReturn(recovered);
        when(first.nextMessage(org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenAnswer(invocation -> { consumerStopped.await(); return null; });

        NatsExecutionEventConsumer consumer = new NatsExecutionEventConsumer(connection,
                mock(ExecutionEventPort.class), command -> { }, new com.fasterxml.jackson.databind.ObjectMapper(),
                "control-plane-execution-events", AsyncConsumerTracing.noop());
        consumer.start();
        var listener = org.mockito.ArgumentCaptor.forClass(ConnectionListener.class);
        verify(connection).addConnectionListener(listener.capture());

        listener.getValue().connectionEvent(connection, ConnectionListener.Events.RESUBSCRIBED);
        verify(first, never()).unsubscribe();

        listener.getValue().connectionEvent(connection, ConnectionListener.Events.RESUBSCRIBED);
        verify(first).unsubscribe();
        consumer.close();
        consumerStopped.countDown();
    }

    @Test
    void dispatchesDifferentTasksInParallel() throws Exception {
        ExecutionEventPort executionEvents = mock(ExecutionEventPort.class);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            started.countDown();
            release.await(1, TimeUnit.SECONDS);
            return null;
        }).when(executionEvents).apply(any(), any(), any());
        NatsExecutionEventConsumer consumer = new NatsExecutionEventConsumer(
                mock(JetStream.class), executionEvents, command -> { },
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), "test-consumer",
                AsyncConsumerTracing.noop(), 2, 2);

        assertThat(consumer.dispatch(message(taskEventJson(java.util.UUID.randomUUID())))).isTrue();
        assertThat(consumer.dispatch(message(taskEventJson(java.util.UUID.randomUUID())))).isTrue();
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        release.countDown();
        assertThat(consumer.awaitIdle(Duration.ofSeconds(1))).isTrue();
        verify(executionEvents, org.mockito.Mockito.times(2)).apply(any(), any(), any());
        consumer.close();
    }

    @Test
    void keepsExecutionEventsForOneTaskInOrder() throws Exception {
        ExecutionEventPort executionEvents = mock(ExecutionEventPort.class);
        java.util.UUID taskId = java.util.UUID.randomUUID();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown();
                release.await(1, TimeUnit.SECONDS);
            }
            return null;
        }).when(executionEvents).apply(any(), any(), any());
        NatsExecutionEventConsumer consumer = new NatsExecutionEventConsumer(
                mock(JetStream.class), executionEvents, command -> { },
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), "test-consumer",
                AsyncConsumerTracing.noop(), 2, 2);

        assertThat(consumer.dispatch(message(taskEventJson(taskId)))).isTrue();
        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(consumer.dispatch(message(taskEventJson(taskId)))).isTrue();
        Thread.sleep(50);
        assertThat(calls).hasValue(1);

        release.countDown();
        assertThat(consumer.awaitIdle(Duration.ofSeconds(1))).isTrue();
        assertThat(calls).hasValue(2);
        consumer.close();
    }

    @Test
    void leavesExecutionMessageUnacknowledgedWhenApplicationFails() throws Exception {
        ExecutionEventPort executionEvents = mock(ExecutionEventPort.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(executionEvents).apply(any(), any(), any());
        NatsExecutionEventConsumer consumer = new NatsExecutionEventConsumer(
                mock(JetStream.class), executionEvents, command -> { },
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), "test-consumer",
                AsyncConsumerTracing.noop(), 1, 1);
        Message message = message(taskEventJson());

        assertThat(consumer.dispatch(message)).isTrue();
        assertThat(consumer.awaitIdle(Duration.ofSeconds(1))).isTrue();
        verify(message, never()).ack();
        verify(message).nakWithDelay(Duration.ofMillis(250));
        consumer.close();
    }

    private static String taskEventJson() {
        return taskEventJson(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"));
    }

    private static Message message(String payload) {
        Message message = mock(Message.class);
        when(message.getData()).thenReturn(payload.getBytes(StandardCharsets.UTF_8));
        return message;
    }

    private static String taskEventJson(java.util.UUID taskId) {
        return """
                {
                  "schemaVersion": 1,
                  "type": "TASK",
                  "taskId": "%s",
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
                """.formatted(taskId);
    }
}
