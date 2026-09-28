package io.agentteams.controlplane.command;

import io.agentteams.application.api.CommandResult;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CommandIdempotencyRecord(
        UUID commandId, String tenantId, String commandType, String idempotencyKey,
        String requestHash, CommandResult result, Instant createdAt) {
    public CommandIdempotencyRecord {
        Objects.requireNonNull(commandId, "commandId");
        requireText(tenantId, "tenantId");
        requireText(commandType, "commandType");
        requireText(idempotencyKey, "idempotencyKey");
        requireText(requestHash, "requestHash");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
