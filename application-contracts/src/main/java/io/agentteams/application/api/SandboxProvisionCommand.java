package io.agentteams.application.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Provider-neutral, idempotent request to provision one task-attempt sandbox. */
public record SandboxProvisionCommand(
        UUID taskId,
        UUID attemptId,
        SandboxProfile profile,
        Duration ttl,
        String template,
        Instant requestedAt,
        String idempotencyKey,
        SandboxPolicy policy) {

    private static final Duration MAX_TTL = Duration.ofHours(24);

    public SandboxProvisionCommand(UUID taskId, UUID attemptId, SandboxProfile profile, Duration ttl,
            String template, Instant requestedAt, String idempotencyKey) {
        this(taskId, attemptId, profile, ttl, template, requestedAt, idempotencyKey,
                new SandboxRequest(taskId, attemptId, profile, ttl, template, requestedAt, idempotencyKey).policy());
    }

    public SandboxProvisionCommand {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        if (policy.profile() != profile || !policy.ttl().equals(ttl)) {
            throw new IllegalArgumentException("sandbox policy must match command profile and ttl");
        }
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("ttl must be greater than zero and no more than 24 hours");
        }
        template = required(template, "template", 128);
        idempotencyKey = required(idempotencyKey, "idempotencyKey", 256);
    }

    public static SandboxProvisionCommand from(SandboxRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return new SandboxProvisionCommand(request.taskId(), request.attemptId(), request.profile(), request.ttl(),
                request.template(), request.requestedAt(), request.idempotencyKey(), request.policy());
    }

    public Instant expiresAt() {
        return requestedAt.plus(ttl);
    }

    private static String required(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be non-blank and no more than " + maxLength
                    + " characters");
        }
        return value.trim();
    }
}
