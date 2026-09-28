package io.agentteams.application.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** Immutable event contract shared by Control Plane channel projections. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UnifiedEventEnvelope(
        @JsonProperty("eventId") UUID eventId,
        @JsonProperty("eventType") String eventType,
        @JsonProperty("eventVersion") int eventVersion,
        @JsonProperty("tenantId") String tenantId,
        @JsonProperty("aggregateType") String aggregateType,
        @JsonProperty("aggregateId") UUID aggregateId,
        @JsonProperty("aggregateVersion") long aggregateVersion,
        @JsonProperty("conversationId") UUID conversationId,
        @JsonProperty("taskId") UUID taskId,
        @JsonProperty("occurredAt") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
        @JsonProperty("actor") CommandActor actor,
        @JsonProperty("payload") JsonNode payload,
        @JsonProperty("visibility") String visibility,
        @JsonProperty("traceId") String traceId) {

    public UnifiedEventEnvelope {
        ProtocolValidation.require(eventId, "eventId");
        ProtocolValidation.requireText(eventType, "eventType");
        if (eventVersion <= 0) {
            throw new IllegalArgumentException("eventVersion must be positive");
        }
        ProtocolValidation.requireText(tenantId, "tenantId");
        ProtocolValidation.requireText(aggregateType, "aggregateType");
        ProtocolValidation.require(aggregateId, "aggregateId");
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must not be negative");
        }
        ProtocolValidation.require(occurredAt, "occurredAt");
        ProtocolValidation.require(actor, "actor");
        ProtocolValidation.require(payload, "payload");
        ProtocolValidation.requireText(visibility, "visibility");
        ProtocolValidation.requireText(traceId, "traceId");
    }
}
