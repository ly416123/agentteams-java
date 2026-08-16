package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.protobuf.ByteString;
import io.agentteams.contracts.v1.ServerMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskAssignedCommandHandlerTest {

    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID ATTEMPT_ID = UUID.randomUUID();
    private static final UUID ASSIGNMENT_ID = UUID.randomUUID();
    private static final UUID LEASE_ID = UUID.randomUUID();
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-16T00:00:00Z");
    private static final Instant LEASE_EXPIRES_AT = Instant.parse("2026-08-16T00:30:00Z");

    @Test
    void ignoresNonTaskAssignedEventsWithoutParsingOrDelivering() {
        CommandDeliveryService delivery = mock(CommandDeliveryService.class);
        TaskAssignedCommandHandler handler = new TaskAssignedCommandHandler(delivery);

        boolean handled = handler.handle("TaskCreated", TASK_ID.toString(), "not-json", OCCURRED_AT,
                knownTaskFields());

        assertThat(handled).isFalse();
        verifyNoInteractions(delivery);
    }

    @Test
    void mapsValidAssignmentPayloadAndDeliversTaskAssignedCommand() {
        CommandDeliveryService delivery = mock(CommandDeliveryService.class);
        TaskAssignedCommandHandler handler = new TaskAssignedCommandHandler(delivery);

        boolean handled = handler.handle("TaskAssigned", TASK_ID.toString(), payloadJson(), OCCURRED_AT,
                knownTaskFields());

        assertThat(handled).isTrue();
        var command = org.mockito.ArgumentCaptor.forClass(ServerMessage.class);
        verify(delivery).deliver(eq(AGENT_ID.toString()), command.capture());
        ServerMessage message = command.getValue();
        assertThat(message.hasTaskAssigned()).isTrue();
        assertThat(message.getTaskAssigned().getMetadata().getTaskId()).isEqualTo(TASK_ID.toString());
        assertThat(message.getTaskAssigned().getMetadata().getAttemptId()).isEqualTo(ATTEMPT_ID.toString());
        assertThat(message.getTaskAssigned().getMetadata().getLeaseId()).isEqualTo(LEASE_ID.toString());
        assertThat(message.getTaskAssigned().getMetadata().getAgentId()).isEqualTo(AGENT_ID.toString());
        assertThat(message.getTaskAssigned().getTaskType()).isEqualTo("summarize");
        assertThat(message.getTaskAssigned().getInputJson()).isEqualTo(ByteString.copyFromUtf8("{\"text\":\"hello\"}"));
        assertThat(message.getTaskAssigned().getRequiredCapabilitiesList()).containsExactly("llm", "workspace");
        assertThat(message.getTaskAssigned().getLeaseExpiresAt().getSeconds()).isEqualTo(Instant.parse(
                "2026-08-16T00:30:00Z").getEpochSecond());
    }

    @Test
    void preservesUnknownPayloadFieldsForForwardCompatibleConsumers() {
        CommandDeliveryService delivery = mock(CommandDeliveryService.class);
        TaskAssignedCommandHandler handler = new TaskAssignedCommandHandler(delivery);

        TaskAssignedCommandPayload parsed = handler.parsePayload(TASK_ID.toString(), payloadJson(), OCCURRED_AT);

        assertThat(parsed.extensions()).containsKey("futureField");
        assertThat(parsed.extensions().get("futureField").asText()).isEqualTo("kept");
    }

    @Test
    void rejectsMalformedUuidBeforeDelivery() {
        CommandDeliveryService delivery = mock(CommandDeliveryService.class);
        TaskAssignedCommandHandler handler = new TaskAssignedCommandHandler(delivery);

        assertThatThrownBy(() -> handler.handle("TaskAssigned", TASK_ID.toString(), payloadJson("attemptId", "bad"),
                OCCURRED_AT, knownTaskFields()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attemptId");
        verifyNoInteractions(delivery);
    }

    @Test
    void rejectsMalformedJsonWithExplicitPayloadError() {
        CommandDeliveryService delivery = mock(CommandDeliveryService.class);
        TaskAssignedCommandHandler handler = new TaskAssignedCommandHandler(delivery);

        assertThatThrownBy(() -> handler.handle("TaskAssigned", TASK_ID.toString(), "{", OCCURRED_AT,
                knownTaskFields()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloadJson");
        verifyNoInteractions(delivery);
    }

    @Test
    void rejectsBlankAgentIdBeforeDelivery() {
        CommandDeliveryService delivery = mock(CommandDeliveryService.class);
        TaskAssignedCommandHandler handler = new TaskAssignedCommandHandler(delivery);

        assertThatThrownBy(() -> handler.handle("TaskAssigned", TASK_ID.toString(), payloadJson("agentId", " "),
                OCCURRED_AT, knownTaskFields()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentId");
        verifyNoInteractions(delivery);
    }

    private static TaskAssignedCommandHandler.KnownTaskFields knownTaskFields() {
        return new TaskAssignedCommandHandler.KnownTaskFields("summarize",
                ByteString.copyFromUtf8("{\"text\":\"hello\"}"), List.of("llm", "workspace"), LEASE_EXPIRES_AT);
    }

    private static String payloadJson() {
        return payloadJson(null, null);
    }

    private static String payloadJson(String replacementField, String replacementValue) {
        String agentId = replacementField != null && replacementField.equals("agentId") ? replacementValue
                : AGENT_ID.toString();
        String attemptId = replacementField != null && replacementField.equals("attemptId") ? replacementValue
                : ATTEMPT_ID.toString();
        return "{\"taskId\":\"" + TASK_ID + "\",\"agentId\":\"" + agentId
                + "\",\"attemptId\":\"" + attemptId + "\",\"assignmentId\":\"" + ASSIGNMENT_ID
                + "\",\"leaseId\":\"" + LEASE_ID + "\",\"spec\":{\"requiredCapabilities\":[\"llm\"]}"
                + ",\"futureField\":\"kept\"}";
    }
}
