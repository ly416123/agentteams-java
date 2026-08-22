package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Message;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
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
