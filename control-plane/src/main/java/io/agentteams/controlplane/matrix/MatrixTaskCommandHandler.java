package io.agentteams.controlplane.matrix;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.application.api.TaskCommandPort;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Permission;
import java.util.Objects;
import java.util.UUID;

/** Maps the supported Matrix command subset to the application task boundary. */
public final class MatrixTaskCommandHandler implements MatrixCommandHandler {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TaskCommandPort tasks;

    public MatrixTaskCommandHandler(TaskCommandPort tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    @Override
    public String handle(String sender, MatrixCommand command) {
        throw new MatrixIdentityBindingException("Matrix identity is required for task commands");
    }

    @Override
    public String handle(MatrixIdentity identity, MatrixCommand command) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(command, "command");
        if (!(command instanceof MatrixCommand.Start start)) {
            throw new MatrixCommandHandlingException("Matrix command is not implemented");
        }

        new AuthorizationService().require(identity.principal().subject(), Permission.TASK_CREATE,
                identity.principal().permissions());
        TaskCommandPort.TaskCreationResult created = tasks.create("matrix:" + UUID.randomUUID(),
                new TaskCommandPort.TaskCreateCommand(start.title(),
                        "Started from Matrix by " + identity.matrixUserId(), scopedSpec(identity),
                        identity.principal().subject(), "matrix"));
        return "created task " + created.taskId();
    }

    private static String scopedSpec(MatrixIdentity identity) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode scope = root.putObject("scope");
        scope.put("tenant", identity.principal().scope().tenant());
        scope.put("project", identity.principal().scope().project());
        scope.put("team", identity.principal().scope().team());
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException error) {
            throw new MatrixCommandHandlingException("Matrix task scope could not be encoded", error);
        }
    }
}
