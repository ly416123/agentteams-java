package io.agentteams.controlplane.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
