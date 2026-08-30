package io.agentteams.operator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** HTTP adapter for failed rollout discovery and rollback confirmation. */
public final class HttpWorkerOperationRecovery implements WorkerOperationRecovery {
    private static final String TOKEN_HEADER = "X-AgentTeams-Internal-Token";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final URI controlPlaneUrl;
    private final String internalToken;
    private final Duration requestTimeout;

    public HttpWorkerOperationRecovery(HttpClient httpClient, ObjectMapper mapper, URI controlPlaneUrl,
            String internalToken, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.controlPlaneUrl = Objects.requireNonNull(controlPlaneUrl, "controlPlaneUrl");
        this.internalToken = internalToken == null ? "" : internalToken.trim();
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (this.internalToken.isBlank()) throw new IllegalArgumentException("operator internal token must not be blank");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (controlPlaneUrl.getScheme() == null || controlPlaneUrl.getHost() == null) {
            throw new IllegalArgumentException("control plane URL must be absolute");
        }
    }

    @Override
    public Optional<FailedWorkerOperation> failed(UUID agentId) {
        Objects.requireNonNull(agentId, "agentId");
        try {
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(failedPath(agentId))
                    .timeout(requestTimeout).header(TOKEN_HEADER, internalToken).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Optional.empty();
            FailedOperation operation = mapper.readValue(response.body(), FailedOperation.class);
            return Optional.of(new FailedWorkerOperation(operation.id(), operation.agentId(),
                    operation.previousStableSpec(), operation.version()));
        } catch (IOException error) {
            return Optional.empty();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    @Override
    public void rollback(UUID operationId, long expectedVersion) {
        Objects.requireNonNull(operationId, "operationId");
        try {
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(rollbackPath(operationId))
                    .timeout(requestTimeout).header(TOKEN_HEADER, internalToken)
                    .header("Idempotency-Key", "operator-rollback-" + operationId + "-" + expectedVersion)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                            Map.of("expectedVersion", expectedVersion))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Control Plane rollback endpoint returned HTTP "
                        + response.statusCode());
            }
        } catch (IOException error) {
            throw new IllegalStateException("Control Plane rollback request failed", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Control Plane rollback request interrupted", error);
        }
    }

    private URI failedPath(UUID agentId) {
        return controlPlaneUrl.resolve("/internal/v1/worker-operations/failed/" + agentId);
    }

    private URI rollbackPath(UUID operationId) {
        return controlPlaneUrl.resolve("/internal/v1/worker-operations/" + operationId + "/rollback");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FailedOperation(UUID id, UUID agentId, String previousStableSpec, long version) { }
}
