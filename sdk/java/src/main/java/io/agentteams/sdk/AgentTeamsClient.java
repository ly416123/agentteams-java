package io.agentteams.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Small Java 17 client for the stable AgentTeams public API. */
public final class AgentTeamsClient {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final HttpClient httpClient;
    private final String baseUrl;
    private final Supplier<String> accessToken;
    private final int maxRetries;
    private final long retryDelayMillis;

    public AgentTeamsClient(String baseUrl, Supplier<String> accessToken) {
        this(baseUrl, accessToken, 2, 100);
    }

    public AgentTeamsClient(String baseUrl, Supplier<String> accessToken,
            int maxRetries, long retryDelayMillis) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        if (maxRetries < 0 || retryDelayMillis < 0) {
            throw new IllegalArgumentException("retry settings must not be negative");
        }
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.accessToken = accessToken;
        this.maxRetries = maxRetries;
        this.retryDelayMillis = retryDelayMillis;
    }

    public Project createProject(CreateProjectRequest request) {
        return send("POST", "/api/v1/projects", request, Project.class, false);
    }

    public Task getTask(UUID taskId) {
        requireId(taskId);
        return send("GET", "/api/v1/tasks/" + taskId, null, Task.class, true);
    }

    public Task cancelTask(UUID taskId, LifecycleRequest request) {
        requireId(taskId);
        return send("POST", "/api/v1/tasks/" + taskId + "/cancel", request, Task.class, false);
    }

    public Task cancelTask(UUID taskId, LifecycleRequest request, boolean retrySafe) {
        requireId(taskId);
        return send("POST", "/api/v1/tasks/" + taskId + "/cancel", request, Task.class, retrySafe);
    }

    public TaskProgressSnapshot getTaskProgress(UUID taskId, UUID runId) {
        requireId(taskId);
        requireId(runId);
        return send("GET", "/api/v1/tasks/" + taskId + "/runs/" + runId + "/progress?phase=EXECUTION",
                null, TaskProgressSnapshot.class, true);
    }

    public TaskResultManifest getTaskResult(UUID taskId, UUID runId) {
        requireId(taskId);
        requireId(runId);
        return send("GET", "/api/v1/tasks/" + taskId + "/runs/" + runId + "/result?visibility=REQUESTER",
                null, TaskResultManifest.class, true);
    }

    public List<TaskProcessEvent> listTaskProcessEvents(UUID taskId, UUID runId, long after) {
        requireId(taskId);
        requireId(runId);
        if (after < 0) throw new IllegalArgumentException("after cursor must be non-negative");
        TaskProcessEvent[] events = send("GET", "/api/v1/tasks/" + taskId + "/runs/" + runId
                + "/process-events?after=" + after + "&visibility=REQUESTER", null, TaskProcessEvent[].class, true);
        return List.of(events);
    }

    private <T> T send(String method, String path, Object body, Class<T> responseType,
            boolean retrySafe) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (accessToken != null) {
            String token = accessToken.get();
            if (token != null && !token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }
        }
        if ("GET".equals(method)) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .header("Idempotency-Key", "sdk-" + UUID.randomUUID())
                    .method(method, HttpRequest.BodyPublishers.ofString(writeJson(body)));
        }

        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response = sendHttp(request.build());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return readJson(response.body(), responseType);
            }
            if (retryable(method, response.statusCode(), retrySafe) && attempt < maxRetries) {
                pause(retryDelayMillis * (1L << attempt));
                continue;
            }
            throw apiError(response.statusCode(), response.body());
        }
    }

    private HttpResponse<String> sendHttp(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new ApiErrorException(503, "TRANSPORT_UNAVAILABLE", "AgentTeams API unavailable", null, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiErrorException(503, "REQUEST_INTERRUPTED", "AgentTeams API request interrupted", null, exception);
        }
    }

    private static boolean retryable(String method, int status, boolean retrySafe) {
        return ("GET".equals(method) || retrySafe)
                && (status == 429 || status == 500 || status == 502 || status == 503 || status == 504);
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalArgumentException("request body cannot be serialized", exception);
        }
    }

    private static <T> T readJson(String body, Class<T> responseType) {
        try {
            return JSON.readValue(body, responseType);
        } catch (IOException exception) {
            throw new ApiErrorException(502, "INVALID_RESPONSE", "AgentTeams API returned invalid JSON", null, exception);
        }
    }

    private static ApiErrorException apiError(int status, String body) {
        try {
            JsonNode payload = JSON.readTree(body);
            return new ApiErrorException(status,
                    text(payload, "code", codeForStatus(status)),
                    text(payload, "message", "AgentTeams API request failed"),
                    text(payload, "correlationId", null), null);
        } catch (IOException | RuntimeException ignored) {
            return new ApiErrorException(status, codeForStatus(status),
                    "AgentTeams API request failed", null, null);
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    private static String codeForStatus(int status) {
        return switch (status) {
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 409 -> "CONFLICT";
            case 429 -> "RATE_LIMITED";
            case 503 -> "UNAVAILABLE_DEPENDENCY";
            default -> "REQUEST_FAILED";
        };
    }

    private static void pause(long milliseconds) {
        if (milliseconds == 0) {
            return;
        }
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiErrorException(503, "REQUEST_INTERRUPTED", "AgentTeams API request interrupted", null, exception);
        }
    }

    private static void requireId(UUID value) {
        if (value == null) {
            throw new IllegalArgumentException("id must not be null");
        }
    }

    public record CreateProjectRequest(String name) { }

    public record LifecycleRequest(Long expectedVersion, String actor, String source) { }

    public record Project(UUID id, String tenantId, String name, String status, String createdBy) { }

    public record Task(UUID id, String title, String description, String phase, int priority,
            java.time.Instant createdAt, java.time.Instant updatedAt, long version) { }

    public record TaskProgressSnapshot(String phase, long completed, long total, int progress,
            String waitingReason) { }

    public record TaskProcessEvent(UUID eventId, UUID taskId, UUID runId, long sequence, String eventType,
            String visibility, java.time.Instant occurredAt, String correlationId, String payload, String payloadRef) { }

    public record TaskResultManifest(UUID taskId, UUID runId, String status, String summary,
            java.util.List<ArtifactMetadata> artifacts) { }

    public record ArtifactMetadata(String name, String storageRef, String contentType, long sizeBytes,
            String sha256, long version, String stage, String visibility) { }

    public static final class ApiErrorException extends RuntimeException {
        private final int status;
        private final String code;
        private final String correlationId;

        private ApiErrorException(int status, String code, String message, String correlationId, Throwable cause) {
            super(message, cause);
            this.status = status;
            this.code = code;
            this.correlationId = correlationId;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        public String correlationId() {
            return correlationId;
        }
    }
}
