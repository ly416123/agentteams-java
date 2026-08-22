package io.agentteams.controlplane.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

public record EventEnvelope(
        @JsonProperty("event_id") UUID eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("aggregate_type") String aggregateType,
        @JsonProperty("aggregate_id") UUID aggregateId,
        @JsonProperty("aggregate_version") long aggregateVersion,
        @JsonProperty("occurred_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
        @JsonProperty("payload") JsonNode payload,
        @JsonProperty("correlation_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String correlationId,
        @JsonProperty("traceparent") @JsonInclude(JsonInclude.Include.NON_EMPTY) String traceparent,
        @JsonProperty("tracestate") @JsonInclude(JsonInclude.Include.NON_EMPTY) String tracestate) {

    public EventEnvelope(UUID eventId, String eventType, String aggregateType, UUID aggregateId,
            long aggregateVersion, Instant occurredAt, JsonNode payload) {
        this(eventId, eventType, aggregateType, aggregateId, aggregateVersion, occurredAt, payload,
                "unknown", "", "");
    }

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payload, "payload");
        if (eventType.isBlank() || aggregateType.isBlank() || aggregateVersion < 0) {
            throw new IllegalArgumentException("invalid event envelope");
        }
    }
}
