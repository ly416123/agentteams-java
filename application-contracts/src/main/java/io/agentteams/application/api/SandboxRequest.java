package io.agentteams.application.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable request to provision a sandbox for one task attempt.
 *
 * <p>The attempt is the ownership boundary. A retry therefore gets a new request
 * and cannot accidentally reuse a sandbox belonging to an earlier attempt.</p>
 */
public record SandboxRequest(
        UUID taskId,
        UUID attemptId,
        SandboxProfile profile,
        Duration ttl,
        String template,
        Instant requestedAt,
        String idempotencyKey) {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
    private static final Duration MAX_TTL = Duration.ofHours(24);
    private static final String DEFAULT_TEMPLATE = "default";

    public SandboxRequest {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("ttl must be greater than zero and no more than 24 hours");
        }
        if (template == null || template.isBlank() || template.length() > 128) {
            throw new IllegalArgumentException("template must be non-blank and no more than 128 characters");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 256) {
            throw new IllegalArgumentException("idempotencyKey must be non-blank and no more than 256 characters");
        }
    }

    public static SandboxRequest defaults(UUID taskId, UUID attemptId, Instant requestedAt) {
        return of(taskId, attemptId, SandboxProfile.NONE, DEFAULT_TTL, DEFAULT_TEMPLATE, requestedAt);
    }

    public static SandboxRequest of(UUID taskId, UUID attemptId, SandboxProfile profile, Duration ttl,
            String template, Instant requestedAt) {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        return new SandboxRequest(taskId, attemptId, profile, ttl, template, requestedAt,
                "task-attempt:" + attemptId);
    }

    public Instant expiresAt() {
        return requestedAt.plus(ttl);
    }
}
