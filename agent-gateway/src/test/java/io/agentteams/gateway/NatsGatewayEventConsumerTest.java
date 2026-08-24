package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Message;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.JetStream;
import io.nats.client.JetStreamSubscription;
import io.nats.client.PushSubscribeOptions;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class NatsGatewayEventConsumerTest {

    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID ATTEMPT_ID = UUID.randomUUID();
    private static final UUID ASSIGNMENT_ID = UUID.randomUUID();
    private static final UUID LEASE_ID = UUID.randomUUID();

    @Test
    void acknowledgesValidAssignmentOnlyAfterDurableCommandDelivery() {
        CommandDeliveryService delivery = mock(CommandDeliveryService.class);
        TaskAssignedCommandHandler commandHandler = new TaskAssignedCommandHandler(delivery);
        NatsGatewayEventConsumer consumer = new NatsGatewayEventConsumer(commandHandler, new ObjectMapper());
        Message message = message(eventJson());

        assertThat(consumer.process(message)).isTrue();

        verify(delivery).deliver(org.mockito.ArgumentMatchers.eq(AGENT_ID.toString()),
                org.mockito.ArgumentMatchers.argThat(command -> command.hasTaskAssigned()));
        verify(message).ack();
    }

    @Test
    void carriesAsyncTraceContextIntoDurableAgentCommand() {
        CommandDeliveryService delivery = mock(CommandDeliveryService.class);
        TaskAssignedCommandHandler commandHandler = new TaskAssignedCommandHandler(delivery);
        NatsGatewayEventConsumer consumer = new NatsGatewayEventConsumer(commandHandler, new ObjectMapper());
        Message message = message(eventJson().replace("\"payload\":{",
                "\"correlation_id\":\"matrix-42\",\"traceparent\":\"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\",\"tracestate\":\"vendor=value\",\"payload\":{"));

        assertThat(consumer.process(message)).isTrue();

        var command = org.mockito.ArgumentCaptor.forClass(io.agentteams.contracts.v1.ServerMessage.class);
        verify(delivery).deliver(org.mockito.ArgumentMatchers.eq(AGENT_ID.toString()), command.capture());
        assertThat(command.getValue().getTaskAssigned().getMetadata().getCorrelationId()).isEqualTo("matrix-42");
        assertThat(command.getValue().getTaskAssigned().getMetadata().getTraceparent())
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(command.getValue().getTaskAssigned().getMetadata().getTracestate()).isEqualTo("vendor=value");
    }

    @Test
    void bridgesPropagatedContextIntoConsumerSpan() {
        CommandDeliveryService delivery = mock(CommandDeliveryService.class);
        Tracer tracer = mock(Tracer.class);
        Propagator propagator = mock(Propagator.class);
        Span.Builder builder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        when(builder.name(org.mockito.ArgumentMatchers.anyString())).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        when(propagator.extract(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(builder);
        when(tracer.withSpan(span)).thenReturn(scope);
        NatsGatewayEventConsumer consumer = new NatsGatewayEventConsumer(new TaskAssignedCommandHandler(delivery),
                new ObjectMapper(), new AsyncConsumerTracing(tracer, propagator));
        Message message = message(eventJson().replace("\"payload\":{",
                "\"correlation_id\":\"matrix-42\",\"traceparent\":\"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\","
                        + "\"tracestate\":\"vendor=value\",\"payload\":{"));

        assertThat(consumer.process(message)).isTrue();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, String>> carrier = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(propagator).extract(carrier.capture(), org.mockito.ArgumentMatchers.any());
        assertThat(carrier.getValue()).containsEntry("traceparent",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .containsEntry("tracestate", "vendor=value");
        verify(builder).name("agentteams.nats.gateway.consume");
        verify(scope).close();
        verify(span).end();
    }

    @Test
    void acknowledgesKnownButIrrelevantEventsWithoutDeliveringCommands() {
        CommandDeliveryService delivery = mock(CommandDeliveryService.class);
        NatsGatewayEventConsumer consumer = new NatsGatewayEventConsumer(
                new TaskAssignedCommandHandler(delivery), new ObjectMapper());
        Message message = message(eventJson().replace("TaskAssigned", "TaskCreated"));

        assertThat(consumer.process(message)).isFalse();

        verify(message).ack();
        verifyNoInteractions(delivery);
    }

    @Test
    void doesNotAcknowledgeMalformedEnvelopeSoJetStreamCanRedeliverIt() {
        CommandDeliveryService delivery = mock(CommandDeliveryService.class);
        NatsGatewayEventConsumer consumer = new NatsGatewayEventConsumer(
                new TaskAssignedCommandHandler(delivery), new ObjectMapper());
        Message message = message("{\"event_type\":\"TaskAssigned\"}");

        assertThatThrownBy(() -> consumer.process(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event_id");

        org.mockito.Mockito.verify(message, org.mockito.Mockito.never()).ack();
        verifyNoInteractions(delivery);
    }

    @Test
    void acknowledgesWorkerExecutionEventsOnConfigSubscriptionWithoutParsingAsOutboxEnvelope() {
        CommandDeliveryService delivery = mock(CommandDeliveryService.class);
        NatsGatewayEventConsumer consumer = new NatsGatewayEventConsumer(
                new TaskAssignedCommandHandler(delivery), new ObjectMapper());
        Message message = message("{\"schemaVersion\":1,\"type\":\"TASK\","
                + "\"taskId\":\"" + TASK_ID + "\",\"taskExecution\":{}}");

        consumer.processConfig(message);

        verify(message).ack();
        verifyNoInteractions(delivery);
    }

    @Test
    void rebuildsDurableSubscriptionsAfterNatsResubscribedEvent() throws Exception {
        CommandDeliveryService delivery = mock(CommandDeliveryService.class);
        Connection connection = mock(Connection.class);
        JetStream jetStream = mock(JetStream.class);
        JetStreamSubscription firstTaskSubscription = mock(JetStreamSubscription.class);
        JetStreamSubscription firstConfigSubscription = mock(JetStreamSubscription.class);
        JetStreamSubscription secondTaskSubscription = mock(JetStreamSubscription.class);
        JetStreamSubscription secondConfigSubscription = mock(JetStreamSubscription.class);
        CountDownLatch consumerStopped = new CountDownLatch(1);
        when(connection.jetStream()).thenReturn(jetStream);
        when(jetStream.subscribe(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(PushSubscribeOptions.class)))
                .thenReturn(firstTaskSubscription, firstConfigSubscription, secondTaskSubscription,
                        secondConfigSubscription);
        when(firstTaskSubscription.nextMessage(org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenAnswer(invocation -> { consumerStopped.await(); return null; });
        when(secondTaskSubscription.nextMessage(org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenAnswer(invocation -> { consumerStopped.await(); return null; });

        NatsGatewayEventConsumer consumer = new NatsGatewayEventConsumer(connection,
                new TaskAssignedCommandHandler(delivery), new ConfigChangedCommandHandler(delivery, new ObjectMapper()),
                new ObjectMapper(), "task.events.*", "gateway-tasks", "agent.events.*", "gateway-config",
                GatewayMetricsPort.noop(), AsyncConsumerTracing.noop());
        consumer.start();

        var listener = org.mockito.ArgumentCaptor.forClass(ConnectionListener.class);
        verify(connection).addConnectionListener(listener.capture());
        listener.getValue().connectionEvent(connection, ConnectionListener.Events.RESUBSCRIBED);

        verify(firstTaskSubscription).unsubscribe();
        verify(firstConfigSubscription).unsubscribe();
        verify(jetStream, org.mockito.Mockito.times(4)).subscribe(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(PushSubscribeOptions.class));
        consumer.close();
        consumerStopped.countDown();
    }

    private static Message message(String payload) {
        Message message = mock(Message.class);
        when(message.getData()).thenReturn(payload.getBytes(StandardCharsets.UTF_8));
        return message;
    }

    private static String eventJson() {
        return "{\"event_id\":\"" + UUID.randomUUID() + "\",\"event_type\":\"TaskAssigned\","
                + "\"aggregate_type\":\"task\",\"aggregate_id\":\"" + TASK_ID + "\","
                + "\"aggregate_version\":2,\"occurred_at\":\"2026-08-16T00:00:00Z\",\"payload\":{"
                + "\"taskId\":\"" + TASK_ID + "\",\"agentId\":\"" + AGENT_ID + "\","
                + "\"attemptId\":\"" + ATTEMPT_ID + "\",\"assignmentId\":\"" + ASSIGNMENT_ID + "\","
                + "\"leaseId\":\"" + LEASE_ID + "\",\"spec\":{},\"taskType\":\"summarize\","
                + "\"inputJson\":{\"text\":\"hello\"},\"requiredCapabilities\":[\"llm\"],"
                + "\"leaseExpiresAt\":\"" + Instant.parse("2026-08-16T00:30:00Z") + "\"}}";
    }
}
