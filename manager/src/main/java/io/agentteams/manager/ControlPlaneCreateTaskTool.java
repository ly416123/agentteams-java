package io.agentteams.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.application.api.TaskCommandPort;
import io.agentteams.application.api.TaskCommandPort.TaskCreationResult;
import io.agentteams.manager.security.ManagerPrincipal;
import io.agentteams.manager.security.ManagerRequestContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.HexFormat;

/** Typed Manager tool: model output becomes a Task only after validation and idempotent persistence. */
public final class ControlPlaneCreateTaskTool {
    private final TaskCommandPort taskCommands;
    private final ObjectMapper mapper;

    public ControlPlaneCreateTaskTool(TaskCommandPort taskCommands, ObjectMapper mapper) {
        this.taskCommands = Objects.requireNonNull(taskCommands, "taskCommands");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public TaskCreationResult create(CreateTaskIntent intent) {
        return create(intent, null);
    }

    public TaskCreationResult create(CreateTaskIntent intent, ManagerToolRegistry.ToolContext context) {
        Objects.requireNonNull(intent, "intent");
        return taskCommands.create(idempotencyKey(intent), new TaskCommandPort.TaskCreateCommand(intent.title(),
                intent.description(), spec(intent, ManagerRequestContext.require(), context), "manager", "manager"));
    }

    private String spec(CreateTaskIntent intent, ManagerPrincipal principal, ManagerToolRegistry.ToolContext context) {
        ObjectNode root = mapper.createObjectNode();
        root.put("taskType", "manager-request");
        ObjectNode scope = root.putObject("scope");
        scope.put("tenant", principal.tenantId());
        scope.put("project", principal.projectId());
        scope.put("team", principal.teamId());
        if (context != null && context.sessionId() != null) {
            // The session identifier is metadata only; the user prompt remains outside the task spec.
            // It lets Control Plane correlate Manager planning facts with the first execution run.
            root.put("managerSessionId", context.sessionId());
        }
        ObjectNode input = root.putObject("inputJson");
        input.put("description", intent.description());
        ArrayNode capabilities = root.putArray("requiredCapabilities");
        intent.requiredCapabilities().forEach(capabilities::add);
        root.put("priority", intent.priority());
        return root.toString();
    }

    private static String idempotencyKey(CreateTaskIntent intent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String value = intent.intent() + "\n" + intent.title() + "\n" + intent.description() + "\n"
                    + intent.requiredCapabilities() + "\n" + intent.priority();
            return "manager-" + HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
