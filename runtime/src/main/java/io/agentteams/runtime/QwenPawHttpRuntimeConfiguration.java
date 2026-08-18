package io.agentteams.runtime;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Connection and request defaults for the official QwenPaw HTTP/SSE API. */
public record QwenPawHttpRuntimeConfiguration(
        URI endpoint,
        String agentId,
        String authorizationToken,
        Duration connectTimeout,
        String userId,
        String channel) {

    public QwenPawHttpRuntimeConfiguration {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!"http".equalsIgnoreCase(endpoint.getScheme())
                && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("endpoint must use http or https");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("channel must not be blank");
        }
        authorizationToken = authorizationToken == null || authorizationToken.isBlank()
                ? null : authorizationToken;
    }

    public QwenPawHttpRuntimeConfiguration(URI endpoint) {
        this(endpoint, "default", null, Duration.ofSeconds(10), "agentteams", "console");
    }
}
