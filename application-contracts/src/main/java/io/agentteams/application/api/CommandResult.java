package io.agentteams.application.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** Stable result contract for accepted, replayed, rejected, or failed commands. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandResult(
        @JsonProperty("commandId") UUID commandId,
        @JsonProperty("status") CommandStatus status,
        @JsonProperty("deduplicated") boolean deduplicated,
        @JsonProperty("taskId") UUID taskId,
        @JsonProperty("aggregateVersion") long aggregateVersion,
        @JsonProperty("traceId") String traceId) {

    public CommandResult {
        ProtocolValidation.require(commandId, "commandId");
        ProtocolValidation.require(status, "status");
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must not be negative");
        }
        ProtocolValidation.requireText(traceId, "traceId");
    }
}
