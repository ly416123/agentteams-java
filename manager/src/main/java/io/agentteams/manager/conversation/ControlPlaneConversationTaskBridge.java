package io.agentteams.manager.conversation;

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
import java.util.UUID;

/** Creates a queued AgentScope task for a UUID-scoped management conversation. */
public final class ControlPlaneConversationTaskBridge implements ConversationTaskBridge {
    private final TaskCommandPort commands;
    private final ObjectMapper mapper;

    public ControlPlaneConversationTaskBridge(TaskCommandPort commands, ObjectMapper mapper) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public TaskCreationResult createAndQueue(ConversationRuntimePort.Context context,
            ConversationOwner owner, String content, String idempotencyKey) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        UUID teamId = parseTeamId(context.team());
        if (teamId == null) {
            throw new IllegalArgumentException("conversation Team must be a UUID");
        }
        ManagerPrincipal principal = ManagerRequestContext.requireAuthenticated();

        String key = "conversation-task-" + context.sessionId() + "-" + shortHash(idempotencyKey);
        ObjectNode spec = mapper.createObjectNode();
        spec.put("taskType", "agentscope");
        spec.put("teamId", teamId.toString());
        spec.put("sourceConversationId", context.sessionId().toString());
        ObjectNode scope = spec.putObject("scope");
        // The task API receives the original bearer token and therefore validates
        // the resource scope against the token's legacy claims. Keep those claims
        // in spec.scope while retaining canonical UUIDs in the routing fields.
        scope.put("tenant", principal.tenantId());
        scope.put("project", principal.projectId());
        scope.put("team", principal.teamId());
        ObjectNode input = spec.putObject("inputJson");
        input.put("prompt", content);
        input.put("conversationId", context.sessionId().toString());
        ArrayNode capabilities = spec.putArray("requiredCapabilities");

        TaskCreationResult created = commands.create(key, new TaskCommandPort.TaskCreateCommand(
                "Conversation task " + context.sessionId(), content, spec.toString(), "manager", "conversation"));
        TaskCreationResult queued = commands.queue(created.taskId(), created.version(), key + "-queue");
        return queued;
    }

    private static UUID parseTeamId(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
