package io.agentteams.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** HTTP adapter for the token-protected Control Plane Gateway observation API. */
public final class HttpGatewayWorkerOperationObservationReporter
        implements GatewayWorkerOperationObservationReporter {
    private static final String TOKEN_HEADER = "X-AgentTeams-Internal-Token";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final URI controlPlaneUrl;
    private final String internalToken;
    private final Duration requestTimeout;

    public HttpGatewayWorkerOperationObservationReporter(HttpClient httpClient, ObjectMapper mapper,
            GatewayOperationProperties properties) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(properties, "properties");
        this.controlPlaneUrl = Objects.requireNonNull(properties.getControlPlaneUrl(), "controlPlaneUrl");
        this.internalToken = properties.getInternalToken() == null ? "" : properties.getInternalToken().trim();
        this.requestTimeout = Objects.requireNonNull(properties.getRequestTimeout(), "requestTimeout");
        if (internalToken.isBlank()) throw new IllegalArgumentException("gateway internal token must not be blank");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (controlPlaneUrl.getScheme() == null || controlPlaneUrl.getHost() == null) {
            throw new IllegalArgumentException("control plane URL must be absolute");
        }
    }

    @Override
    public void report(ConnectionRegistry.ConnectionSnapshot connection, boolean online, Instant observedAt) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(observedAt, "observedAt");
        UUID agentId;
        try {
            agentId = UUID.fromString(connection.agentId());
        } catch (IllegalArgumentException error) {
            return;
        }
        try {
            HttpResponse<String> active = httpClient.send(HttpRequest.newBuilder(activePath(agentId))
                    .timeout(requestTimeout).header(TOKEN_HEADER, internalToken).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (active.statusCode() != 200) return;
            ActiveOperation operation = mapper.readValue(active.body(), ActiveOperation.class);
            if (!"ROLLOUT".equals(operation.type())) return;
            String body = mapper.writeValueAsString(Map.of(
                    "expectedVersion", operation.version(),
                    "online", online,
                    "specDigest", text(connection.specDigest()),
                    "runtime", text(connection.runtime()),
                    "configRevision", text(connection.configRevision()),
                    "secretGeneration", text(connection.secretGeneration()),
                    "observedAt", observedAt.toString()));
            httpClient.send(HttpRequest.newBuilder(gatewayPath(operation.id()))
                    .timeout(requestTimeout).header(TOKEN_HEADER, internalToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException error) {
            // The Gateway connection projection remains authoritative; a later
            // heartbeat/reconnect retries a transient Control Plane failure.
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private URI activePath(UUID agentId) {
        return controlPlaneUrl.resolve("/internal/v1/worker-operations/active/" + agentId);
    }

    private URI gatewayPath(UUID operationId) {
        return controlPlaneUrl.resolve("/internal/v1/worker-operations/" + operationId + "/gateway");
    }

    private static String text(String value) { return value == null ? "" : value; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ActiveOperation(UUID id, String type, long version) { }
}
