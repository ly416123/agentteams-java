package io.agentteams.controlplane.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.CommandActor;
import io.agentteams.application.api.UnifiedEventEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatrixEventProjectorTest {
    private static final Instant NOW = Instant.parse("2026-09-28T06:00:00Z");
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CONVERSATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void routesConversationEventsToTheActiveConversationRoomWithoutRawPayload() {
        MatrixRoomBindingRepository bindings = mock(MatrixRoomBindingRepository.class);
        MatrixOutboundRepository outbound = mock(MatrixOutboundRepository.class);
        MatrixRoomBinding binding = binding(MatrixRoomContextType.CONVERSATION, CONVERSATION_ID,
                "!conversation:example.org");
        when(bindings.findActiveByContext("tenant-a", MatrixRoomContextType.CONVERSATION, CONVERSATION_ID))
                .thenReturn(Optional.of(binding));
        when(outbound.enqueue(any(UUID.class), eq(binding.tenantId()), eq(binding.id()), eq(binding.roomId()),
                eq("TaskCreated"), argThat(body -> body.contains("TaskCreated") && !body.contains("secret-payload")),
                eq(NOW)))
                .thenReturn(true);

        MatrixEventProjector projector = new MatrixEventProjector(bindings, outbound,
                Clock.fixed(NOW, ZoneOffset.UTC));
        MatrixEventProjector.ProjectionResult result = projector.project(event("TaskCreated", CONVERSATION_ID, null,
                "PUBLIC", JSON.createObjectNode().put("secret", "secret-payload")));

        assertThat(result.status()).isEqualTo(MatrixEventProjector.ProjectionStatus.QUEUED);
        assertThat(result.roomId()).isEqualTo(binding.roomId());
    }

    @Test
    void prefersTaskRoomAndReturnsDuplicateForOutboxReplay() {
        MatrixRoomBindingRepository bindings = mock(MatrixRoomBindingRepository.class);
        MatrixOutboundRepository outbound = mock(MatrixOutboundRepository.class);
        MatrixRoomBinding binding = binding(MatrixRoomContextType.TASK, TASK_ID, "!task:example.org");
        when(bindings.findActiveByContext("tenant-a", MatrixRoomContextType.TASK, TASK_ID))
                .thenReturn(Optional.of(binding));
        when(outbound.enqueue(any(UUID.class), eq(binding.tenantId()), eq(binding.id()), eq(binding.roomId()),
                anyString(), anyString(), eq(NOW)))
                .thenReturn(false);

        MatrixEventProjector projector = new MatrixEventProjector(bindings, outbound,
                Clock.fixed(NOW, ZoneOffset.UTC));
        MatrixEventProjector.ProjectionResult result = projector.project(event("TaskPaused", CONVERSATION_ID, TASK_ID,
                "TEAM", JSON.createObjectNode().put("payloadRef", "task-result:4")));

        assertThat(result.status()).isEqualTo(MatrixEventProjector.ProjectionStatus.DUPLICATE);
        assertThat(result.roomId()).isEqualTo(binding.roomId());
    }

    @Test
    void doesNotProjectInternalEventsOrEventsWithoutAnActiveBinding() {
        MatrixRoomBindingRepository bindings = mock(MatrixRoomBindingRepository.class);
        MatrixOutboundRepository outbound = mock(MatrixOutboundRepository.class);
        MatrixEventProjector projector = new MatrixEventProjector(bindings, outbound,
                Clock.fixed(NOW, ZoneOffset.UTC));

        MatrixEventProjector.ProjectionResult internal = projector.project(event("Secret", CONVERSATION_ID, null,
                "INTERNAL", JSON.createObjectNode().put("secret", "value")));
        MatrixEventProjector.ProjectionResult unbound = projector.project(event("TaskCreated", CONVERSATION_ID, null,
                "PUBLIC", JSON.createObjectNode()));

        assertThat(internal.status()).isEqualTo(MatrixEventProjector.ProjectionStatus.SKIPPED);
        assertThat(unbound.status()).isEqualTo(MatrixEventProjector.ProjectionStatus.SKIPPED);
        verifyNoInteractions(outbound);
    }

    private static UnifiedEventEnvelope event(String type, UUID conversationId, UUID taskId, String visibility,
            com.fasterxml.jackson.databind.JsonNode payload) {
        return new UnifiedEventEnvelope(UUID.fromString("00000000-0000-0000-0000-000000000010"), type, 1,
                "tenant-a", "TASK", taskId == null ? CONVERSATION_ID : taskId, 3, conversationId, taskId, NOW,
                new CommandActor("USER", "alice", "MATRIX"), payload, visibility, "trace-1");
    }

    private static MatrixRoomBinding binding(MatrixRoomContextType type, UUID contextId, String roomId) {
        return new MatrixRoomBinding(UUID.randomUUID(), "org-a", "tenant-a", roomId, type, contextId, PROJECT_ID,
                TEAM_ID, MatrixRoomBindingStatus.ACTIVE, "admin", NOW, NOW, 0);
    }
}
