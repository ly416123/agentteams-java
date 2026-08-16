package io.agentteams.controlplane.matrix;

import java.util.UUID;

public sealed interface MatrixCommand permits MatrixCommand.Start, MatrixCommand.TaskAction {
    record Start(String title) implements MatrixCommand {
        public Start {
            if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        }
    }

    record TaskAction(Action action, UUID taskId) implements MatrixCommand {
        public TaskAction {
            if (action == null || taskId == null) throw new IllegalArgumentException("action and taskId are required");
        }
    }

    enum Action { CANCEL, RETRY, PAUSE, APPROVE, REJECT, STATUS }
}
