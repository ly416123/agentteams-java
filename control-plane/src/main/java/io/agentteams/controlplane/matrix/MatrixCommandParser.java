package io.agentteams.controlplane.matrix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.application.api.CommandActor;
import io.agentteams.application.api.CommandEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

public final class MatrixCommandParser {
    private static final String PREFIX = "!agentteams";
    private static final ObjectMapper JSON = new ObjectMapper();

    public boolean isCommand(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String first = body.trim().split("\\s+", 2)[0];
        return PREFIX.equalsIgnoreCase(first) || (first.startsWith("/") && first.length() > 1);
    }

    public MatrixCommand parse(String body) {
        if (body == null || body.isBlank()) throw new MatrixCommandException("Matrix command is empty");
        String[] tokens = body.trim().split("\\s+", 2);
        String action;
        String argument = null;
        if (PREFIX.equalsIgnoreCase(tokens[0])) {
            if (tokens.length < 2) throw new MatrixCommandException("Matrix command is incomplete");
            String[] commandTokens = tokens[1].split("\\s+", 2);
            action = commandTokens[0].toLowerCase(Locale.ROOT);
            if (commandTokens.length == 2) {
                argument = commandTokens[1].trim();
            }
        } else if (tokens[0].startsWith("/") && tokens[0].length() > 1) {
            action = tokens[0].substring(1).toLowerCase(Locale.ROOT);
            if (tokens.length == 2) {
                argument = tokens[1].trim();
            }
        } else {
            throw new MatrixCommandException("unknown Matrix command");
        }
        if ("start".equals(action)) {
            if (argument == null || argument.isBlank()) throw new MatrixCommandException("start title is required");
            return new MatrixCommand.Start(argument);
        }
        MatrixCommand.TaskAction.Action parsed = switch (action) {
            case "cancel" -> MatrixCommand.TaskAction.Action.CANCEL;
            case "retry" -> MatrixCommand.TaskAction.Action.RETRY;
            case "pause" -> MatrixCommand.TaskAction.Action.PAUSE;
            case "approve" -> MatrixCommand.TaskAction.Action.APPROVE;
            case "reject" -> MatrixCommand.TaskAction.Action.REJECT;
            case "status" -> MatrixCommand.TaskAction.Action.STATUS;
            default -> throw new MatrixCommandException("unsupported Matrix command: " + action);
        };
        if (argument == null || argument.isBlank()) throw new MatrixCommandException("task id is required");
        try {
            return new MatrixCommand.TaskAction(parsed, UUID.fromString(argument));
        } catch (IllegalArgumentException error) {
            throw new MatrixCommandException("task id must be a UUID", error);
        }
    }

    public CommandEnvelope toEnvelope(MatrixIdentity identity, MatrixRoomBinding binding, MatrixCommand command,
            String transactionId, String eventId, long aggregateVersion) {
        if (identity == null || binding == null || command == null) {
            throw new IllegalArgumentException("identity, binding and command are required");
        }
        required(transactionId, "transactionId");
        required(eventId, "eventId");
        if (binding.status() != MatrixRoomBindingStatus.ACTIVE) {
            throw new MatrixCommandException("Matrix room binding is not active");
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.put("roomId", binding.roomId());
        payload.put("contextType", binding.primaryContextType().name());
        payload.put("contextId", binding.primaryContextId().toString());
        UUID conversationId = binding.primaryContextType() == MatrixRoomContextType.CONVERSATION
                ? binding.primaryContextId() : null;
        UUID taskId = null;
        String commandType;
        if (command instanceof MatrixCommand.Start start) {
            commandType = "task.start";
            payload.put("title", start.title());
        } else {
            MatrixCommand.TaskAction action = (MatrixCommand.TaskAction) command;
            taskId = action.taskId();
            if (binding.primaryContextType() == MatrixRoomContextType.TASK
                    && !binding.primaryContextId().equals(taskId)) {
                throw new MatrixCommandException("task is outside the bound Matrix room");
            }
            commandType = "task." + action.action().name().toLowerCase(Locale.ROOT);
            payload.put("action", action.action().name().toLowerCase(Locale.ROOT));
        }
        String idempotencyKey = "matrix:" + transactionId + ":" + eventId;
        UUID commandId = UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8));
        return new CommandEnvelope(commandId, commandType, binding.tenantId(),
                new CommandActor(identity.actorType(), identity.subject(), "MATRIX"), conversationId, taskId, "TASK",
                aggregateVersion, payload, idempotencyKey, "matrix:" + transactionId, Instant.now());
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
