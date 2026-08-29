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
        String channel,
        int maxConcurrentRequests,
        int maxEventsPerSession,
        int maxSessions) {

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
        if (maxConcurrentRequests <= 0) {
            throw new IllegalArgumentException("maxConcurrentRequests must be positive");
        }
        if (maxEventsPerSession <= 0) {
            throw new IllegalArgumentException("maxEventsPerSession must be positive");
        }
        if (maxSessions <= 0) {
            throw new IllegalArgumentException("maxSessions must be positive");
        }
        authorizationToken = authorizationToken == null || authorizationToken.isBlank()
                ? null : authorizationToken;
        if (authorizationToken != null && "http".equalsIgnoreCase(endpoint.getScheme())
                && !isLoopbackHost(endpoint.getHost())) {
            throw new IllegalArgumentException(
                    "authorizationToken requires https unless endpoint is loopback");
        }
    }

    public ConversationRuntimeConfiguration(URI endpoint, String agentId, String authorizationToken,
            Duration connectTimeout, Duration requestTimeout, long maxResponseBytes, String userId,
            String channel) {
        this(endpoint, agentId, authorizationToken, connectTimeout, requestTimeout, maxResponseBytes,
                userId, channel, 128, 10_000, 10_000);
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

    private static boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
    }
}
