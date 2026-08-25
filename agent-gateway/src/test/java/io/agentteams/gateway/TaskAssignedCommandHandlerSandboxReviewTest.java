package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

class TaskAssignedCommandHandlerSandboxReviewTest {
    private static final UUID TASK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ATTEMPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AGENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void mapsAndValidatesSandboxAssignmentOwnerBeforeDelivery() {
        CommandDeliveryService delivery = mock(CommandDeliveryService.class);
        TaskAssignedCommandHandler handler = new TaskAssignedCommandHandler(delivery);

        handler.handle("TaskAssigned", TASK_ID.toString(), payload(TASK_ID, ATTEMPT_ID),
                Instant.parse("2026-08-25T00:00:00Z"), knownTaskFields());

        var command = org.mockito.ArgumentCaptor.forClass(ServerMessage.class);
        verify(delivery).deliver(eq(AGENT_ID.toString()), command.capture());
        assertThat(command.getValue().getTaskAssigned().getSandbox().getProviderSandboxId())
                .isEqualTo("provider-sandbox-1");
        assertThat(command.getValue().getTaskAssigned().getSandbox().getOwnerTaskId())
                .isEqualTo(TASK_ID.toString());
    }

    @Test
    void rejectsSandboxAssignmentWhoseOwnerDoesNotMatchTopLevelTaskOrAttempt() {
        CommandDeliveryService delivery = mock(CommandDeliveryService.class);
        TaskAssignedCommandHandler handler = new TaskAssignedCommandHandler(delivery);

        assertThatThrownBy(() -> handler.handle("TaskAssigned", TASK_ID.toString(),
                payload(TASK_ID, ATTEMPT_ID,
                        UUID.fromString("44444444-4444-4444-4444-444444444444")),
                Instant.parse("2026-08-25T00:00:00Z"), knownTaskFields()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sandbox");
        verifyNoInteractions(delivery);
    }

    @Test
    void refusesExecutableSandboxProfileWhenAssignmentReferenceIsMissing() {
        CommandDeliveryService delivery = mock(CommandDeliveryService.class);
        TaskAssignedCommandHandler handler = new TaskAssignedCommandHandler(delivery);

        assertThatThrownBy(() -> handler.handle("TaskAssigned", TASK_ID.toString(), missingSandboxPayload(),
                Instant.parse("2026-08-25T00:00:00Z"), knownTaskFields()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sandbox assignment is required");
        verifyNoInteractions(delivery);
    }

    private static TaskAssignedCommandHandler.KnownTaskFields knownTaskFields() {
        return new TaskAssignedCommandHandler.KnownTaskFields("chat",
                ByteString.copyFromUtf8("{}"), List.of("workspace"),
                Instant.parse("2026-08-25T00:30:00Z"));
    }

    private static String payload(UUID taskId, UUID attemptId) {
        return payload(taskId, attemptId, attemptId);
    }

    private static String payload(UUID taskId, UUID attemptId, UUID ownerAttemptId) {
        return """
                {"taskId":"%s","agentId":"%s","attemptId":"%s",
                 "assignmentId":"55555555-5555-5555-5555-555555555555",
                 "leaseId":"66666666-6666-6666-6666-666666666666",
                 "spec":{"sandboxProfile":"ISOLATED"},
                 "sandbox":{"sandboxId":"sandbox-1","providerSandboxId":"provider-sandbox-1",
                 "profile":"ISOLATED","status":"READY",
                 "endpointRef":"sandbox://provider/sandbox-1",
                 "expiresAt":"2026-08-25T00:30:00Z",
                 "ownerTaskId":"%s","ownerAttemptId":"%s"}}
                """.formatted(taskId, AGENT_ID, attemptId, taskId, ownerAttemptId)
                .replaceAll("\\s+", "");
    }

    private static String missingSandboxPayload() {
        return """
                {"taskId":"%s","agentId":"%s","attemptId":"%s",
                "assignmentId":"55555555-5555-5555-5555-555555555555",
                "leaseId":"66666666-6666-6666-6666-666666666666",
                "spec":{"sandbox":{"profile":"ISOLATED"}}}"""
                .formatted(TASK_ID, AGENT_ID, ATTEMPT_ID).replaceAll("\\s+", "");
    }
}
