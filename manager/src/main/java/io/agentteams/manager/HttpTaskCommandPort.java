package io.agentteams.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.application.api.TaskCommandPort;
import io.agentteams.manager.security.ManagerRequestContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;

/** Typed HTTP adapter; Manager never reaches Control Plane persistence classes. */
public final class HttpTaskCommandPort implements TaskCommandPort {
    private final URI tasksEndpoint;
    private final HttpClient client;
    private final ObjectMapper mapper;

    public HttpTaskCommandPort(String controlPlaneUrl, HttpClient client, ObjectMapper mapper) {
        if (controlPlaneUrl == null || controlPlaneUrl.isBlank()) {
            throw new IllegalArgumentException("AGENTTEAMS_CONTROL_PLANE_URL must be set");
        }
        this.tasksEndpoint = URI.create(controlPlaneUrl.replaceAll("/+$", "") + "/api/v1/tasks");
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public TaskCreationResult create(String idempotencyKey, TaskCreateCommand command) {
        Objects.requireNonNull(command, "command");
        String bearerToken = ManagerRequestContext.requireBearerToken();
        ObjectNode body = mapper.createObjectNode();
        body.put("title", command.title());
        body.put("description", command.description());
        try {
            body.set("spec", mapper.readTree(command.specJson()));
        } catch (IOException | RuntimeException error) {
            throw new ManagerToolTemporaryFailureException("task specification could not be encoded", error);
        }
        body.put("actor", command.actor());
        body.put("source", command.source());
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(tasksEndpoint)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .header("Authorization", "Bearer " + bearerToken);
        HttpRequest request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409) throw new ManagerToolConflictException("task creation conflicted");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ManagerToolTemporaryFailureException("control plane task API unavailable");
            }
            JsonNode result = mapper.readTree(response.body());
            return new TaskCreationResult(java.util.UUID.fromString(result.path("id").asText()),
                    result.path("phase").asText(), result.path("version").asLong());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ManagerToolTemporaryFailureException("control plane task API interrupted", error);
        } catch (IOException | IllegalArgumentException error) {
            throw new ManagerToolTemporaryFailureException("control plane task API failed", error);
        }
    }
}
