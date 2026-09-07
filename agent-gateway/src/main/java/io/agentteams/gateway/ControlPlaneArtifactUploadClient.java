package io.agentteams.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.ArtifactUploadHttp;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** Calls the Control Plane artifact upload bridge from the Gateway process. */
public final class ControlPlaneArtifactUploadClient implements ArtifactUploadBridge {
    private static final String TOKEN_HEADER = "X-AgentTeams-Internal-Token";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final URI prepareUri;
    private final URI completeUri;
    private final String internalToken;
    private final Duration requestTimeout;

    public ControlPlaneArtifactUploadClient(HttpClient httpClient, ObjectMapper mapper,
            GatewayArtifactUploadProperties properties) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(properties, "properties");
        URI base = Objects.requireNonNull(properties.getControlPlaneUrl(), "controlPlaneUrl");
        String normalized = base.toString().replaceAll("/+$", "");
        this.prepareUri = URI.create(normalized + "/internal/v1/artifacts/uploads");
        this.completeUri = URI.create(normalized + "/internal/v1/artifacts/complete");
        this.internalToken = properties.getInternalToken() == null ? "" : properties.getInternalToken().trim();
        this.requestTimeout = Objects.requireNonNull(properties.getRequestTimeout(), "requestTimeout");
        if (internalToken.isBlank()) {
            throw new IllegalArgumentException("artifact upload internal token must not be blank");
        }
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("artifact upload request timeout must be positive");
        }
        if (base.getScheme() == null || base.getHost() == null) {
            throw new IllegalArgumentException("artifact upload control plane URL must be absolute");
        }
    }

    public ArtifactUploadHttp.PrepareResponse prepare(ArtifactUploadHttp.PrepareRequest request) {
        Objects.requireNonNull(request, "request");
        return send(prepareUri, request, ArtifactUploadHttp.PrepareResponse.class);
    }

    public ArtifactUploadHttp.CompleteResponse complete(ArtifactUploadHttp.CompleteRequest request) {
        Objects.requireNonNull(request, "request");
        return send(completeUri, request, ArtifactUploadHttp.CompleteResponse.class);
    }

    private <T> T send(URI uri, Object payload, Class<T> responseType) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header(TOKEN_HEADER, internalToken)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ControlPlaneInvocationException(
                        "Control Plane artifact endpoint returned HTTP " + response.statusCode(),
                        response.statusCode());
            }
            return mapper.readValue(response.body(), responseType);
        } catch (IOException error) {
            throw new ControlPlaneInvocationException("Control Plane artifact request failed", 0, error);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ControlPlaneInvocationException("Control Plane artifact request interrupted", 0, interrupted);
        }
    }

    /** Carries the Control Plane HTTP status so the gRPC boundary can map it. */
    public static final class ControlPlaneInvocationException extends IllegalStateException {
        private final int statusCode;

        public ControlPlaneInvocationException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public ControlPlaneInvocationException(String message, int statusCode, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }
}
