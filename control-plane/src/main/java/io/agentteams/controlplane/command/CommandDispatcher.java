package io.agentteams.controlplane.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.CommandEnvelope;
import io.agentteams.application.api.CommandResult;
import io.agentteams.application.api.CommandStatus;
import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.conversation.AppendConversationMessageCommand;
import io.agentteams.controlplane.conversation.ConversationRecord;
import io.agentteams.controlplane.conversation.ConversationService;
import io.agentteams.controlplane.conversation.CreateConversationCommand;
import io.agentteams.controlplane.persistence.IdempotencyConflictException;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.service.TaskService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Validates and routes stable cross-channel commands to existing domain services. */
@Service
public final class CommandDispatcher {
    private static final Set<String> SENSITIVE_FIELDS = Set.of("password", "secret", "token", "apikey",
            "authorization", "credential", "clientsecret", "privatekey");
    private final TaskService tasks;
    private final ConversationService conversations;
    private final CommandIdempotencyStore idempotency;
    private final AuditRecorder audit;
    private final ObjectMapper json;

    public CommandDispatcher(TaskService tasks, ConversationService conversations, CommandIdempotencyStore idempotency,
            AuditRecorder audit, ObjectMapper json) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.json = Objects.requireNonNull(json, "json");
    }

    public CommandResult dispatch(ExecutionContext context, CommandEnvelope command) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(command, "command");
        try {
            validate(context, command);
            String requestHash = requestHash(command);
            var existing = idempotency.find(context, command.commandType(), command.idempotencyKey());
            if (existing.isPresent()) {
                if (!existing.get().requestHash().equals(requestHash)) {
                    throw new IdempotencyConflictException(command.idempotencyKey(), command.commandType());
                }
                CommandResult duplicate = new CommandResult(existing.get().result().commandId(), CommandStatus.DUPLICATE,
                        true, existing.get().result().taskId(), existing.get().result().aggregateVersion(),
                        command.traceId());
                audit(context, command, "COMMAND_DUPLICATE");
                return duplicate;
            }
            CommandResult result = execute(context, command);
            idempotency.save(context, new CommandIdempotencyRecord(command.commandId(), context.tenantId(),
                    command.commandType(), command.idempotencyKey(), requestHash, result, command.occurredAt()));
            audit(context, command, "COMMAND_ACCEPTED");
            return result;
        } catch (RuntimeException error) {
            audit(context, command, "COMMAND_REJECTED");
            throw error;
        }
    }

    private CommandResult execute(ExecutionContext context, CommandEnvelope command) {
        return switch (command.commandType()) {
            case "task.start" -> {
                TaskRecord task = tasks.queue(requireId(command.taskId(), "taskId"), command.aggregateVersion(),
                        command.idempotencyKey(), command.actor().id());
                yield accepted(command, task.id(), task.version());
            }
            case "conversation.create" -> {
                String title = text(command.payload(), "title");
                ConversationRecord conversation = conversations.create(context,
                        new CreateConversationCommand(command.commandId(), title, command.occurredAt()));
                yield accepted(command, null, conversation.version());
            }
            case "conversation.message", "conversation.send_message" -> {
                UUID conversationId = requireId(command.conversationId(), "conversationId");
                String content = text(command.payload(), "content");
                String messageType = optionalText(command.payload(), "messageType", "TEXT");
                String channel = optionalText(command.payload(), "channel", command.actor().channel());
                String visibility = optionalText(command.payload(), "visibility", "PUBLIC");
                conversations.appendMessage(context, new AppendConversationMessageCommand(command.commandId(),
                        conversationId, command.actor().type(), command.actor().id(), channel, messageType, content,
                        optionalText(command.payload(), "contentRef", null), visibility, command.occurredAt(),
                        command.idempotencyKey(), command.traceId()));
                yield accepted(command, null, command.aggregateVersion() + 1);
            }
            default -> throw new IllegalArgumentException("unsupported command type: " + command.commandType());
        };
    }

    private void validate(ExecutionContext context, CommandEnvelope command) {
        if (!context.tenantId().equals(command.tenantId())) {
            throw new AuthorizationException("command tenant is outside the caller scope");
        }
        if (!context.subjectId().equals(command.actor().id())) {
            throw new AuthorizationException("command actor does not match the authenticated principal");
        }
        if (containsSensitiveField(command.payload())) {
            throw new IllegalArgumentException("command payload contains sensitive material");
        }
        if (!command.commandType().contains(".") || !command.aggregateType().equals(command.commandType().split("\\.", 2)[0])) {
            throw new IllegalArgumentException("command type and aggregate type do not match");
        }
    }

    private void audit(ExecutionContext context, CommandEnvelope command, String action) {
        audit.record(new AuditEvent(UUID.randomUUID(), command.actor().id(), action, "command",
                command.commandId().toString(), Map.of("commandType", command.commandType(), "tenantId", context.tenantId(),
                        "idempotencyKey", command.idempotencyKey()), command.occurredAt()));
    }

    private String requestHash(CommandEnvelope command) {
        try {
            String canonical = String.join("\u001f", command.commandId().toString(), command.commandType(),
                    command.tenantId(), command.actor().type(), command.actor().id(), command.actor().channel(),
                    String.valueOf(command.conversationId()), String.valueOf(command.taskId()), command.aggregateType(),
                    Long.toString(command.aggregateVersion()), json.writeValueAsString(command.payload()),
                    command.idempotencyKey(), command.traceId(), command.occurredAt().toString());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("command payload could not be canonicalized", error);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static CommandResult accepted(CommandEnvelope command, UUID taskId, long version) {
        return new CommandResult(command.commandId(), CommandStatus.ACCEPTED, false, taskId, version, command.traceId());
    }

    private static UUID requireId(UUID value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode payload, String field, String fallback) {
        JsonNode value = payload.get(field);
        return value == null || value.isNull() ? fallback : text(payload, field);
    }

    private static boolean containsSensitiveField(JsonNode node) {
        if (node == null) return false;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String normalized = field.getKey().replaceAll("[^A-Za-z0-9]", "").toLowerCase();
                if (SENSITIVE_FIELDS.contains(normalized) || containsSensitiveField(field.getValue())) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) if (containsSensitiveField(child)) return true;
        }
        return false;
    }
}
