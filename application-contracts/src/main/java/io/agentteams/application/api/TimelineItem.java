package io.agentteams.application.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/** Safe, stable projection item for a conversation timeline. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimelineItem(
        @JsonProperty("itemId") String itemId,
        @JsonProperty("itemType") String itemType,
        @JsonProperty("conversationId") UUID conversationId,
        @JsonProperty("taskId") UUID taskId,
        @JsonProperty("eventType") String eventType,
        @JsonProperty("actorId") String actorId,
        @JsonProperty("occurredAt") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
        @JsonProperty("sequence") long sequence,
        @JsonProperty("visibility") String visibility,
        @JsonProperty("payloadRef") String payloadRef,
        @JsonProperty("summary") String summary,
        @JsonProperty("sourceVersion") long sourceVersion) {

    public TimelineItem {
        ProtocolValidation.requireText(itemId, "itemId");
        ProtocolValidation.requireText(itemType, "itemType");
        ProtocolValidation.requireText(eventType, "eventType");
        ProtocolValidation.requireText(actorId, "actorId");
        ProtocolValidation.require(occurredAt, "occurredAt");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        ProtocolValidation.requireText(visibility, "visibility");
        if (sourceVersion < 0) {
            throw new IllegalArgumentException("sourceVersion must not be negative");
        }
    }
}
