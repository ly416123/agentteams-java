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
import java.util.logging.Logger;

/** HTTP adapter used by the Operator to consume Worker lifecycle commands. */
public final class HttpWorkerOperationDirectiveReader implements WorkerOperationDirectiveReader {
    private static final String TOKEN_HEADER = "X-AgentTeams-Internal-Token";
    private static final Logger LOGGER = Logger.getLogger(HttpWorkerOperationDirectiveReader.class.getName());

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final URI controlPlaneUrl;
    private final String internalToken;
    private final Duration requestTimeout;

    public HttpWorkerOperationDirectiveReader(HttpClient httpClient, ObjectMapper mapper, URI controlPlaneUrl,
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
    public Optional<WorkerOperationDirective> active(UUID agentId) {
        Objects.requireNonNull(agentId, "agentId");
        try {
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(directivePath(agentId))
                    .timeout(requestTimeout).header(TOKEN_HEADER, internalToken).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) return Optional.empty();
            if (response.statusCode() != 200) {
                LOGGER.warning("Control Plane directive endpoint returned HTTP " + response.statusCode());
                return Optional.empty();
            }
            Operation operation = mapper.readValue(response.body(), Operation.class);
            LOGGER.info("Read Worker operation " + operation.id() + " type=" + operation.type()
                    + " agent=" + operation.agentId());
            return Optional.of(new WorkerOperationDirective(operation.id().toString(), operation.agentId().toString(), operation.type(),
                    operation.requestedSpecDigest(), operation.requestedRuntime(), operation.requestedConfigRevision(),
                    operation.requestedSecretGeneration(), operation.version()));
        } catch (IOException error) {
            LOGGER.warning("Control Plane directive response could not be decoded: " + error.getMessage());
            return Optional.empty();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    @Override
    public void confirmTermination(UUID operationId, long expectedVersion) {
        Objects.requireNonNull(operationId, "operationId");
        try {
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(terminatePath(operationId))
                    .timeout(requestTimeout).header(TOKEN_HEADER, internalToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                            Map.of("expectedVersion", expectedVersion))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 202) {
                throw new IllegalStateException("Control Plane termination endpoint returned HTTP "
                        + response.statusCode());
            }
        } catch (IOException error) {
            throw new IllegalStateException("Control Plane termination request failed", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Control Plane termination request interrupted", error);
        }
    }

    private URI directivePath(UUID agentId) {
        return controlPlaneUrl.resolve("/internal/v1/worker-operations/directive/" + agentId);
    }

    private URI terminatePath(UUID operationId) {
        return controlPlaneUrl.resolve("/internal/v1/worker-operations/" + operationId + "/terminate");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Operation(UUID id, UUID agentId, String type, String requestedSpecDigest,
            String requestedRuntime, String requestedConfigRevision, String requestedSecretGeneration,
            long version) { }
}
