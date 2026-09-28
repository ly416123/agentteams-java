package io.agentteams.controlplane.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentteams.application.api.CommandActor;
import io.agentteams.application.api.CommandEnvelope;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatrixCommandParserTest {
    private final MatrixCommandParser parser = new MatrixCommandParser();

    @Test
    void parsesStartAndTaskActions() {
        UUID taskId = UUID.randomUUID();
        assertThat(parser.parse("!agentteams start investigate incident")).isEqualTo(new MatrixCommand.Start("investigate incident"));
        assertThat(parser.parse("!agentteams status " + taskId))
                .isEqualTo(new MatrixCommand.TaskAction(MatrixCommand.TaskAction.Action.STATUS, taskId));
    }

    @Test
    void rejectsUnknownOrIncompleteCommands() {
        assertThatThrownBy(() -> parser.parse("hello")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("!agentteams cancel not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("UUID");
        assertThatThrownBy(() -> parser.parse("!agentteams start")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void convertsConversationRoomStartToStableCommandEnvelope() {
        UUID conversationId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        MatrixRoomBinding binding = new MatrixRoomBinding(UUID.randomUUID(), "org-1", "tenant-1",
                "!conversation:example.org", MatrixRoomContextType.CONVERSATION, conversationId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"), MatrixRoomBindingStatus.ACTIVE,
                "alice", Instant.parse("2026-09-28T04:00:00Z"), Instant.parse("2026-09-28T04:00:00Z"), 0);
        MatrixIdentity identity = new MatrixIdentity("@alice:example.org",
                new Principal("alice", new AuthorizationService.Scope("tenant-1", "project-1", "team-1"),
                        Set.of("task:create")));

        CommandEnvelope command = parser.toEnvelope(identity, binding, parser.parse("!agentteams start investigate"),
                "txn-1", "event-1", 0);

        assertThat(command.commandType()).isEqualTo("task.start");
        assertThat(command.aggregateType()).isEqualTo("TASK");
        assertThat(command.conversationId()).isEqualTo(conversationId);
        assertThat(command.actor()).isEqualTo(new CommandActor("USER", "alice", "MATRIX"));
        assertThat(command.idempotencyKey()).isEqualTo("matrix:txn-1:event-1");
        assertThat(command.payload().path("title").asText()).isEqualTo("investigate");
        assertThat(command.payload().path("roomId").asText()).isEqualTo("!conversation:example.org");
    }

    @Test
    void preservesServiceActorTypeForAppserviceBot() {
        UUID taskId = UUID.fromString("00000000-0000-0000-0000-000000000004");
        MatrixRoomBinding binding = new MatrixRoomBinding(UUID.randomUUID(), "org-1", "tenant-1",
                "!task:example.org", MatrixRoomContextType.TASK, taskId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"), MatrixRoomBindingStatus.ACTIVE,
                "service", Instant.parse("2026-09-28T04:00:00Z"), Instant.parse("2026-09-28T04:00:00Z"), 0);
        MatrixIdentity bot = new MatrixIdentity("@agentteams:example.org",
                new Principal("matrix-bot", new AuthorizationService.Scope("tenant-1", "project-1", "team-1"),
                        Set.of("task:pause")), "SERVICE");

        CommandEnvelope command = parser.toEnvelope(bot, binding,
                parser.parse("!agentteams pause " + taskId), "txn-bot", "event-bot", 7);

        assertThat(command.actor()).isEqualTo(new CommandActor("SERVICE", "matrix-bot", "MATRIX"));
        assertThat(command.taskId()).isEqualTo(taskId);
    }
}
