package io.agentteams.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.sdk.signing.Canonicalizer;
import io.agentteams.sdk.signing.HmacSha256Signer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Small Java 17 client for the AgentTeams public API. */
public final class AgentTeamsClient {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final HttpClient httpClient; private final String baseUrl; private final Supplier<String> accessToken;
    private final String accessKeyId; private final String accessKeySecret; private final String externalOrganizationId;
    private final String externalUserId; private final int maxRetries; private final long retryDelayMillis;
    public AgentTeamsClient(String baseUrl, Supplier<String> accessToken) { this(baseUrl, accessToken, 2, 100); }
    public AgentTeamsClient(String baseUrl, Supplier<String> accessToken, int maxRetries, long retryDelayMillis) { this(baseUrl, null, null, null, null, accessToken, maxRetries, retryDelayMillis); }
    public AgentTeamsClient(String baseUrl, String accessKeyId, String accessKeySecret, String externalOrganizationId) { this(baseUrl, accessKeyId, accessKeySecret, externalOrganizationId, 2, 100); }
    public AgentTeamsClient(String baseUrl, String accessKeyId, String accessKeySecret, String externalOrganizationId, int maxRetries, long retryDelayMillis) { this(baseUrl, accessKeyId, accessKeySecret, externalOrganizationId, null, null, maxRetries, retryDelayMillis); }
    private AgentTeamsClient(String baseUrl, String accessKeyId, String accessKeySecret, String externalOrganizationId, String externalUserId, Supplier<String> accessToken, int maxRetries, long retryDelayMillis) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl must not be blank");
        if (maxRetries < 0 || retryDelayMillis < 0) throw new IllegalArgumentException("retry settings must not be negative");
        boolean signed = accessKeyId != null || accessKeySecret != null || externalOrganizationId != null;
        if (signed && (blank(accessKeyId) || blank(accessKeySecret) || blank(externalOrganizationId))) throw new IllegalArgumentException("accessKeyId, accessKeySecret and externalOrganizationId are required");
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(); this.baseUrl = baseUrl.replaceAll("/$", ""); this.accessToken = accessToken; this.accessKeyId = accessKeyId; this.accessKeySecret = accessKeySecret; this.externalOrganizationId = externalOrganizationId; this.externalUserId = externalUserId; this.maxRetries = maxRetries; this.retryDelayMillis = retryDelayMillis;
    }
    public AgentTeamsClient asUser(String externalUserId) { if (blank(externalUserId)) throw new IllegalArgumentException("externalUserId must not be blank"); return new AgentTeamsClient(baseUrl, accessKeyId, accessKeySecret, externalOrganizationId, externalUserId, accessToken, maxRetries, retryDelayMillis); }
    public Provisioning provisioning() { requireSignedUser(); return new Provisioning(); }
    public Project createProject(CreateProjectRequest request) { return send("POST", "/api/v1/projects", request, Project.class, false, null); }
    public Project createProject(CreateProjectRequest request, String idempotencyKey, boolean retrySafe) { return send("POST", "/api/v1/projects", request, Project.class, retrySafe, idempotencyKey); }
    public Task getTask(UUID taskId) { requireId(taskId); return send("GET", "/api/v1/tasks/" + taskId, null, Task.class, true, null); }
    public Task cancelTask(UUID taskId, LifecycleRequest request) { return cancelTask(taskId, request, false); }
    public Task cancelTask(UUID taskId, LifecycleRequest request, boolean retrySafe) { requireId(taskId); return send("POST", "/api/v1/tasks/" + taskId + "/cancel", request, Task.class, retrySafe, null); }
    public TaskProgressSnapshot getTaskProgress(UUID taskId, UUID runId) { requireId(taskId); requireId(runId); return send("GET", "/api/v1/tasks/" + taskId + "/runs/" + runId + "/progress?phase=EXECUTION", null, TaskProgressSnapshot.class, true, null); }
    public TaskResultManifest getTaskResult(UUID taskId, UUID runId) { requireId(taskId); requireId(runId); return send("GET", "/api/v1/tasks/" + taskId + "/runs/" + runId + "/result?visibility=REQUESTER", null, TaskResultManifest.class, true, null); }
    public List<TaskProcessEvent> listTaskProcessEvents(UUID taskId, UUID runId, long after) { requireId(taskId); requireId(runId); if (after < 0) throw new IllegalArgumentException("after cursor must be non-negative"); TaskProcessEvent[] e = send("GET", "/api/v1/tasks/" + taskId + "/runs/" + runId + "/process-events?after=" + after + "&visibility=REQUESTER", null, TaskProcessEvent[].class, true, null); return List.of(e); }
    private <T> T send(String method, String path, Object body, Class<T> responseType, boolean retrySafe, String idempotencyKey) {
        String bodyJson = body == null ? "" : writeJson(body); String bodyHash = sha256(bodyJson); String timestamp = Long.toString(Instant.now().getEpochSecond()); String nonce = UUID.randomUUID().toString();
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30)).header("Accept", "application/json");
        if (accessKeyId != null) { String signature = new HmacSha256Signer(accessKeySecret).sign(Canonicalizer.canonical(method, URI.create(path), externalOrganizationId, externalUserId, timestamp, nonce, bodyHash)); request.header("Authorization", "AT-HMAC-SHA256 Credential=" + accessKeyId + ",SignedHeaders=...").header("X-AT-Timestamp", timestamp).header("X-AT-Nonce", nonce).header("X-AT-Organization-Id", externalOrganizationId).header("X-AT-User-Id", externalUserId).header("X-AT-Content-SHA256", bodyHash).header("X-AT-Signature", signature); }
        else if (accessToken != null) { String token = accessToken.get(); if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token); }
        if ("GET".equals(method)) request.GET(); else request.header("Content-Type", "application/json").header("Idempotency-Key", idempotencyKey == null ? "sdk-" + UUID.randomUUID() : idempotencyKey).method(method, HttpRequest.BodyPublishers.ofString(bodyJson));
        for (int attempt = 0; ; attempt++) { HttpResponse<String> response = sendHttp(request.build()); if (response.statusCode() >= 200 && response.statusCode() < 300) return readJson(response.body(), responseType); if (retryable(method, response.statusCode(), retrySafe) && attempt < maxRetries) { pause(retryDelayMillis * (1L << attempt)); continue; } throw apiError(response.statusCode(), response.body()); }
    }
    private HttpResponse<String> sendHttp(HttpRequest request) { try { return httpClient.send(request, HttpResponse.BodyHandlers.ofString()); } catch (IOException e) { throw new ApiErrorException(503, "TRANSPORT_UNAVAILABLE", "AgentTeams API unavailable", null, null, e); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiErrorException(503, "REQUEST_INTERRUPTED", "AgentTeams API request interrupted", null, null, e); } }
    private static boolean retryable(String method, int status, boolean retrySafe) { return ("GET".equals(method) || retrySafe) && (status == 429 || status == 500 || status == 502 || status == 503 || status == 504); }
    static String sha256(String value) { return HmacSha256Signer.sha256(value); }
    private static String writeJson(Object value) { try { return JSON.writeValueAsString(value); } catch (IOException e) { throw new IllegalArgumentException("request body cannot be serialized", e); } }
    private static <T> T readJson(String body, Class<T> type) { if (type == Void.class || body == null || body.isBlank()) return null; try { return JSON.readValue(body, type); } catch (IOException e) { throw new ApiErrorException(502, "INVALID_RESPONSE", "AgentTeams API returned invalid JSON", null, null, e); } }
    private static ApiErrorException apiError(int status, String body) { try { JsonNode p = JSON.readTree(body); return new ApiErrorException(status, text(p, "code", codeForStatus(status)), text(p, "message", "AgentTeams API request failed"), text(p, "correlationId", null), p == null ? null : p.get("details"), null); } catch (IOException | RuntimeException e) { return new ApiErrorException(status, codeForStatus(status), "AgentTeams API request failed", null, null, null); } }
    private static String text(JsonNode n, String f, String fallback) { JsonNode v = n == null ? null : n.get(f); return v == null || v.isNull() ? fallback : v.asText(fallback); }
    private static String codeForStatus(int s) { return switch (s) { case 401 -> "UNAUTHENTICATED"; case 403 -> "FORBIDDEN"; case 409 -> "CONFLICT"; case 429 -> "RATE_LIMITED"; case 503 -> "UNAVAILABLE_DEPENDENCY"; default -> "REQUEST_FAILED"; }; }
    private static void pause(long ms) { if (ms == 0) return; try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiErrorException(503, "REQUEST_INTERRUPTED", "AgentTeams API request interrupted", null, null, e); } }
    private static void requireId(UUID id) { if (id == null) throw new IllegalArgumentException("id must not be null"); }
    private void requireSignedUser() { if (accessKeyId == null || blank(externalUserId)) throw new IllegalStateException("provisioning requires a signed client asUser(externalUserId)"); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    public final class Provisioning {
        private Provisioning() { }
        public ProvisioningUser initializeUser(ProvisioningRequest request, String idempotencyKey, boolean retrySafe) { return send("POST", "/api/v1/provisioning/users", request, ProvisioningUser.class, retrySafe, idempotencyKey); }
        public ProvisioningUser updateUser(String targetExternalUserId, ProvisioningRequest request, String idempotencyKey, boolean retrySafe) { requireExternal(targetExternalUserId); return send("PUT", "/api/v1/provisioning/users/" + targetExternalUserId, request, ProvisioningUser.class, retrySafe, idempotencyKey); }
        public ProvisioningUser disableUser(String targetExternalUserId, String idempotencyKey, boolean retrySafe) { requireExternal(targetExternalUserId); return send("POST", "/api/v1/provisioning/users/" + targetExternalUserId + "/disable", null, ProvisioningUser.class, retrySafe, idempotencyKey); }
        public List<Membership> listMemberships(String targetExternalUserId) { requireExternal(targetExternalUserId); Membership[] result = send("GET", "/api/v1/provisioning/users/" + targetExternalUserId + "/memberships", null, Membership[].class, true, null); return List.of(result); }
        private void requireExternal(String id) { if (blank(id)) throw new IllegalArgumentException("externalUserId must not be blank"); }
    }
    public record CreateProjectRequest(String name) { } public record LifecycleRequest(Long expectedVersion, String actor, String source) { } public record ProvisioningRequest(String externalUserId, String displayName) { } public record ProvisioningUser(String externalUserId, String status) { } public record Membership(String projectId, String role) { }
    public record Project(UUID id, String tenantId, String name, String status, String createdBy) { } public record Task(UUID id, String title, String description, String phase, int priority, java.time.Instant createdAt, java.time.Instant updatedAt, long version) { } public record TaskProgressSnapshot(String phase, long completed, long total, int progress, String waitingReason) { } public record TaskProcessEvent(UUID eventId, UUID taskId, UUID runId, long sequence, String eventType, String visibility, java.time.Instant occurredAt, String correlationId, String payload, String payloadRef) { } public record TaskResultManifest(UUID taskId, UUID runId, String status, String summary, java.util.List<ArtifactMetadata> artifacts) { } public record ArtifactMetadata(String name, String storageRef, String contentType, long sizeBytes, String sha256, long version, String stage, String visibility) { }
    public static final class ApiErrorException extends RuntimeException { private final int status; private final String code; private final String correlationId; private final JsonNode details; private ApiErrorException(int status, String code, String message, String correlationId, JsonNode details, Throwable cause) { super(message, cause); this.status = status; this.code = code; this.correlationId = correlationId; this.details = details; } public int status() { return status; } public String code() { return code; } public String correlationId() { return correlationId; } public JsonNode details() { return details; } }
}
