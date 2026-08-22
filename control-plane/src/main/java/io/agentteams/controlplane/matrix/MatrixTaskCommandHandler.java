package io.agentteams.controlplane.matrix;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.application.api.TaskCommandPort;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Permission;
import io.agentteams.controlplane.service.TaskService;
import java.util.Objects;
import java.util.UUID;

/** Maps the supported Matrix command subset to the application task boundary. */
public final class MatrixTaskCommandHandler implements MatrixCommandHandler {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TaskCommandPort tasks;
    private final TaskService taskService;

    public MatrixTaskCommandHandler(TaskCommandPort tasks) {
        this(tasks, null);
    }

    public MatrixTaskCommandHandler(TaskCommandPort tasks, TaskService taskService) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.taskService = taskService;
    }

    @Override
    public String handle(String sender, MatrixCommand command) {
        throw new MatrixIdentityBindingException("Matrix identity is required for task commands");
    }

    @Override
    public String handle(MatrixIdentity identity, MatrixCommand command) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(command, "command");
        if (command instanceof MatrixCommand.Start start) {
            new AuthorizationService().require(identity.principal().subject(), Permission.TASK_CREATE,
                    identity.principal().permissions());
            TaskCommandPort.TaskCreationResult created = tasks.create("matrix:" + UUID.randomUUID(),
                    new TaskCommandPort.TaskCreateCommand(start.title(),
                            "Started from Matrix by " + identity.matrixUserId(), scopedSpec(identity),
                            identity.principal().subject(), "matrix"));
            return "created task " + created.taskId();
        }

        MatrixCommand.TaskAction action = (MatrixCommand.TaskAction) command;
        return switch (action.action()) {
            case STATUS -> status(identity, action.taskId());
            case CANCEL -> cancel(identity, action.taskId());
            case RETRY, PAUSE, APPROVE, REJECT -> throw new MatrixCommandHandlingException(
                    "Matrix task action " + action.action().name().toLowerCase() +
                            " is not supported by the current task lifecycle");
        };
    }

    private String status(MatrixIdentity identity, UUID taskId) {
        requireTaskPermission(identity, Permission.TASK_READ);
        TaskRecord task = scopedTask(identity, taskId);
        return "task " + task.id() + ": " + task.phase().name() + " v" + task.version() + " " + task.title();
    }

    private String cancel(MatrixIdentity identity, UUID taskId) {
        requireTaskPermission(identity, Permission.TASK_CANCEL);
        TaskRecord current = scopedTask(identity, taskId);
        if (taskService == null) {
            throw new MatrixCommandHandlingException("task command service is not configured");
        }
        TaskRecord cancelled = taskService.cancel(taskId, current.version(), "matrix:cancel:" + UUID.randomUUID(),
                identity.principal().subject(), "matrix");
        return "cancelled task " + cancelled.id();
    }

    private TaskRecord scopedTask(MatrixIdentity identity, UUID taskId) {
        if (taskService == null) {
            throw new MatrixCommandHandlingException("task query service is not configured");
        }
        TaskRecord task = taskService.get(Objects.requireNonNull(taskId, "taskId"));
        new AuthorizationService().requireScope(identity.principal(), task.specJson());
        return task;
    }

    private static void requireTaskPermission(MatrixIdentity identity, Permission permission) {
        new AuthorizationService().require(identity.principal().subject(), permission,
                identity.principal().permissions());
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
