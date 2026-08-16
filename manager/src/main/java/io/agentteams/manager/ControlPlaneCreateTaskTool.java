package io.agentteams.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.service.TaskService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.HexFormat;

/** Typed Manager tool: model output becomes a Task only after validation and idempotent persistence. */
public final class ControlPlaneCreateTaskTool {
    private final TaskService taskService;
    private final ObjectMapper mapper;

    public ControlPlaneCreateTaskTool(TaskService taskService, ObjectMapper mapper) {
        this.taskService = Objects.requireNonNull(taskService, "taskService");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public TaskRecord create(CreateTaskIntent intent) {
        Objects.requireNonNull(intent, "intent");
        return taskService.create(idempotencyKey(intent), new TaskService.TaskInput(intent.title(),
                intent.description(), spec(intent), "manager", "deepseek"));
    }

    private String spec(CreateTaskIntent intent) {
        ObjectNode root = mapper.createObjectNode();
        root.put("taskType", "manager-request");
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
