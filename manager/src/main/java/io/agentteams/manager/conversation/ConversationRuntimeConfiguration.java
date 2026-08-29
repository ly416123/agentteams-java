package io.agentteams.manager.conversation;

import java.net.URI;
import java.time.Duration;

/** Configuration for the QwenPaw Conversation HTTP/SSE boundary. */
public record ConversationRuntimeConfiguration(
        URI endpoint,
        String agentId,
        String authorizationToken,
        Duration connectTimeout,
        Duration requestTimeout,
        long maxResponseBytes,
        String userId,
        String channel) {

    public ConversationRuntimeConfiguration {
        if (endpoint == null || (!"http".equalsIgnoreCase(endpoint.getScheme())
                && !"https".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalArgumentException("endpoint must use http or https");
        }
        requireText(agentId, "agentId");
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        requireText(userId, "userId");
        requireText(channel, "channel");
        authorizationToken = authorizationToken == null || authorizationToken.isBlank()
                ? null : authorizationToken;
    }

    public ConversationRuntimeConfiguration(URI endpoint, String agentId, String authorizationToken,
            Duration connectTimeout) {
        this(endpoint, agentId, authorizationToken, connectTimeout, Duration.ofMinutes(2),
                4 * 1024 * 1024L, "agentteams", "console");
    }

    public ConversationRuntimeConfiguration(URI endpoint) {
        this(endpoint, "default", null, Duration.ofSeconds(10));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
