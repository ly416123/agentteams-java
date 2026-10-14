package io.agentteams.controlplane.command;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.CommandActor;
import io.agentteams.application.api.CommandEnvelope;
import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.conversation.ConversationService;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.service.TaskService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommandDispatcherMatrixTrustTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExecutionContext CONTEXT =
            new ExecutionContext("org-a", "tenant-a", "project-a", "team-a", "bob");

    @Test
    void rejectsMatrixCommandWhenActorDoesNotMatchAuthenticatedExecutionContext() {
        CommandDispatcher dispatcher = new CommandDispatcher(mock(TaskService.class),
                mock(ConversationService.class), mock(CommandIdempotencyStore.class), mock(AuditRecorder.class), JSON);

        assertThatThrownBy(() -> dispatcher.dispatch(CONTEXT, matrixStart()))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("command actor does not match the authenticated principal");
    }

    private static CommandEnvelope matrixStart() {
        return new CommandEnvelope(UUID.randomUUID(), "task.start", "tenant-a",
                new CommandActor("USER", "alice", "MATRIX"), UUID.randomUUID(), null, "TASK", 0,
                JSON.createObjectNode().put("organizationId", "org-a").put("title", "forged")
                        .set("spec", JSON.createObjectNode().set("scope", JSON.createObjectNode()
                                .put("tenant", "tenant-a").put("project", "project-a").put("team", "team-a"))),
                "matrix:forged:event", "trace-forged", Instant.parse("2026-09-24T00:00:00Z"));
    }
}
