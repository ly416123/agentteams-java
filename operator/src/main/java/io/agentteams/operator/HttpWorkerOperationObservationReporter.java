package io.agentteams.operator;

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

/** HTTP adapter for the token-protected Control Plane rollout observation API. */
public final class HttpWorkerOperationObservationReporter implements WorkerOperationObservationReporter {
    private static final String TOKEN_HEADER = "X-AgentTeams-Internal-Token";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final URI controlPlaneUrl;
    private final String internalToken;
    private final Duration requestTimeout;

    public HttpWorkerOperationObservationReporter(HttpClient httpClient, ObjectMapper mapper,
            URI controlPlaneUrl, String internalToken) {
        this(httpClient, mapper, controlPlaneUrl, internalToken, Duration.ofSeconds(3));
    }

    public HttpWorkerOperationObservationReporter(HttpClient httpClient, ObjectMapper mapper,
            URI controlPlaneUrl, String internalToken, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.controlPlaneUrl = Objects.requireNonNull(controlPlaneUrl, "controlPlaneUrl");
        this.internalToken = internalToken == null ? "" : internalToken.trim();
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (this.internalToken.isBlank()) {
            throw new IllegalArgumentException("operator internal token must not be blank");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }

    @Override
    public void report(Worker worker, WorkerStatus status, Instant observedAt) {
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(observedAt, "observedAt");
        UUID agentId;
        try {
            agentId = UUID.fromString(worker.getSpec().agentId());
        } catch (IllegalArgumentException error) {
            // Legacy CRs may use human-readable IDs and cannot cross the
            // Control Plane's canonical UUID boundary.
            return;
        }
        try {
            HttpResponse<String> active = httpClient.send(HttpRequest.newBuilder(activePath(agentId))
                    .timeout(requestTimeout).header(TOKEN_HEADER, internalToken).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (active.statusCode() != 200) {
                return;
            }
            ActiveOperation operation = mapper.readValue(active.body(), ActiveOperation.class);
            if (!"ROLLOUT".equals(operation.type())) {
                return;
            }
            String body = mapper.writeValueAsString(Map.of(
                    "expectedVersion", operation.version(),
                    "ready", "Ready".equals(status.getPhase()),
                    "specDigest", text(status.getObservedSpecDigest()),
                    "runtime", text(status.getObservedRuntime()),
                    "configRevision", text(status.getObservedConfigRevision()),
                    "secretGeneration", text(status.getObservedSecretGeneration()),
                    "observedAt", observedAt.toString()));
            httpClient.send(HttpRequest.newBuilder(operatorPath(operation.id()))
                    .timeout(requestTimeout).header(TOKEN_HEADER, internalToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException error) {
            // Reconciliation of Kubernetes children remains authoritative;
            // the next bounded reconcile retries a transient CP failure.
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private URI activePath(UUID agentId) {
        return controlPlaneUrl.resolve("/internal/v1/worker-operations/active/" + agentId);
    }

    private URI operatorPath(UUID operationId) {
        return controlPlaneUrl.resolve("/internal/v1/worker-operations/" + operationId + "/operator");
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ActiveOperation(UUID id, String type, long version) {
    }
}
