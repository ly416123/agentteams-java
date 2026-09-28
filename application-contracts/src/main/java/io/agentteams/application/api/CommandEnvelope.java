package io.agentteams.application.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** Stable cross-channel command contract. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandEnvelope(
        @JsonProperty("commandId") UUID commandId,
        @JsonProperty("commandType") String commandType,
        @JsonProperty("tenantId") String tenantId,
        @JsonProperty("actor") CommandActor actor,
        @JsonProperty("conversationId") UUID conversationId,
        @JsonProperty("taskId") UUID taskId,
        @JsonProperty("aggregateType") String aggregateType,
        @JsonProperty("aggregateVersion") long aggregateVersion,
        @JsonProperty("payload") JsonNode payload,
        @JsonProperty("idempotencyKey") String idempotencyKey,
        @JsonProperty("traceId") String traceId,
        @JsonProperty("occurredAt") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt) {

    public CommandEnvelope {
        ProtocolValidation.require(commandId, "commandId");
        ProtocolValidation.requireText(commandType, "commandType");
        ProtocolValidation.requireText(tenantId, "tenantId");
        ProtocolValidation.require(actor, "actor");
        ProtocolValidation.requireText(aggregateType, "aggregateType");
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must not be negative");
        }
        ProtocolValidation.require(payload, "payload");
        ProtocolValidation.requireText(idempotencyKey, "idempotencyKey");
        ProtocolValidation.requireText(traceId, "traceId");
        ProtocolValidation.require(occurredAt, "occurredAt");
    }
}
