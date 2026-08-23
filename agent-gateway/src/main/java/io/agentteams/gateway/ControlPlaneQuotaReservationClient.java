package io.agentteams.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.QuotaReservationHttp;
import io.agentteams.application.api.QuotaReservationPort;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** Calls the durable Control Plane reservation port from the Gateway process. */
public final class ControlPlaneQuotaReservationClient implements QuotaReservationPort {
    private static final String TOKEN_HEADER = "X-AgentTeams-Internal-Token";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final URI acquireUri;
    private final URI releaseUri;
    private final String internalToken;
    private final Duration requestTimeout;

    public ControlPlaneQuotaReservationClient(HttpClient httpClient, ObjectMapper mapper,
            GatewayQuotaProperties properties) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(properties, "properties");
        URI base = Objects.requireNonNull(properties.getControlPlaneUrl(), "controlPlaneUrl");
        String normalized = base.toString().replaceAll("/+$", "");
        this.acquireUri = URI.create(normalized + "/internal/v1/quota/acquire");
        this.releaseUri = URI.create(normalized + "/internal/v1/quota/release");
        this.internalToken = properties.getInternalToken() == null ? "" : properties.getInternalToken().trim();
        this.requestTimeout = Objects.requireNonNull(properties.getRequestTimeout(), "requestTimeout");
        if (internalToken.isBlank()) throw new IllegalArgumentException("quota internal token must not be blank");
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("quota request timeout must be positive");
        }
        if (base.getScheme() == null || base.getHost() == null) {
            throw new IllegalArgumentException("quota control plane URL must be absolute");
        }
    }

    @Override
    public AcquireDecision acquire(AcquireRequest request) {
        Objects.requireNonNull(request, "request");
        QuotaReservationHttp.AcquireResponse response = send(acquireUri,
                new QuotaReservationHttp.AcquireRequest(request.tenantId(), request.projectId(),
                        request.idempotencyKey(), request.estimatedTokens(), request.maxConcurrent(),
                        request.deadline(), request.traceparent(), request.tracestate()),
                QuotaReservationHttp.AcquireResponse.class);
        return new AcquireDecision(response.accepted(), response.reservationId(), response.rejectionDimension(),
                response.retryAfterMillis(), response.protocolError());
    }

    @Override
    public ReleaseDecision release(ReleaseRequest request) {
        Objects.requireNonNull(request, "request");
        QuotaReservationHttp.ReleaseResponse response = send(releaseUri,
                new QuotaReservationHttp.ReleaseRequest(request.tenantId(), request.projectId(),
                        request.reservationId(), request.idempotencyKey(), request.deadline(),
                        request.traceparent(), request.tracestate()),
                QuotaReservationHttp.ReleaseResponse.class);
        return new ReleaseDecision(response.accepted(), response.reservationId(), response.protocolError());
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
                throw new IllegalStateException("Control Plane quota endpoint returned HTTP " + response.statusCode());
            }
            return mapper.readValue(response.body(), responseType);
        } catch (IOException error) {
            throw new IllegalStateException("Control Plane quota request failed", error);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Control Plane quota request interrupted", interrupted);
        }
    }
}
