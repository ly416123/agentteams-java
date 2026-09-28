package io.agentteams.manager.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.manager.ManagerToolConflictException;
import io.agentteams.manager.ManagerToolTemporaryFailureException;
import io.agentteams.manager.security.ManagerRequestContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** HTTP adapter that sends legacy conversation facts through the unified command endpoint. */
public final class HttpControlPlaneConversationPort implements ControlPlaneConversationPort {
    private final URI commandsEndpoint;
    private final HttpClient client;
    private final ObjectMapper mapper;

    public HttpControlPlaneConversationPort(String controlPlaneUrl, HttpClient client, ObjectMapper mapper) {
        if (controlPlaneUrl == null || controlPlaneUrl.isBlank()) {
            throw new IllegalArgumentException("AGENTTEAMS_CONTROL_PLANE_URL must be set");
        }
        this.commandsEndpoint = URI.create(controlPlaneUrl.replaceAll("/+$", "") + "/api/v1/commands");
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void create(CreateRequest request) {
        Objects.requireNonNull(request, "request");
        ObjectNode payload = mapper.createObjectNode().put("title", request.title());
        send(command(request.conversationId(), "conversation.create", request.tenantId(), request.actorId(),
                request.projectId(), request.teamId(), null, "conversation", request.aggregateVersion(), payload,
                request.idempotencyKey(), trace("create", request.conversationId()), request.occurredAt()));
    }

    @Override
    public void appendMessage(MessageRequest request) {
        Objects.requireNonNull(request, "request");
        ObjectNode payload = mapper.createObjectNode().put("content", request.content())
                .put("channel", request.channel()).put("messageType", request.messageType())
                .put("visibility", request.visibility());
        UUID commandId = UUID.nameUUIDFromBytes((request.conversationId() + "\n" + request.idempotencyKey())
                .getBytes(StandardCharsets.UTF_8));
        send(command(commandId, "conversation.send_message", request.tenantId(), request.actorId(),
                request.projectId(), request.teamId(), request.conversationId(), "conversation",
                request.aggregateVersion(), payload, request.idempotencyKey(), trace("message", commandId),
                request.occurredAt()));
    }

    private ObjectNode command(UUID commandId, String type, String tenantId, String actorId, String projectId,
            String teamId, UUID conversationId, String aggregateType, long aggregateVersion, ObjectNode payload,
            String idempotencyKey, String traceId, Instant occurredAt) {
        ObjectNode root = mapper.createObjectNode();
        root.put("commandId", commandId.toString()).put("commandType", type).put("tenantId", tenantId);
        ObjectNode actor = root.putObject("actor").put("type", "USER").put("id", actorId).put("channel", "INTERNAL");
        root.put("projectId", projectId).put("teamId", teamId);
        if (conversationId != null) root.put("conversationId", conversationId.toString());
        root.put("aggregateType", aggregateType).put("aggregateVersion", aggregateVersion);
        root.set("payload", payload);
        root.put("idempotencyKey", idempotencyKey).put("traceId", traceId).put("occurredAt", occurredAt.toString());
        return root;
    }

    private void send(ObjectNode command) {
        String bearerToken = ManagerRequestContext.requireBearerToken();
        HttpRequest request = HttpRequest.newBuilder(commandsEndpoint)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearerToken)
                .POST(HttpRequest.BodyPublishers.ofString(command.toString()))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409) throw new ManagerToolConflictException("conversation command conflicted");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ManagerToolTemporaryFailureException("control plane conversation API unavailable");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ManagerToolTemporaryFailureException("control plane conversation API interrupted", error);
        } catch (IOException error) {
            throw new ManagerToolTemporaryFailureException("control plane conversation API failed", error);
        }
    }

    private static String trace(String operation, UUID id) {
        return "manager-conversation-" + operation + "-" + id;
    }
}
