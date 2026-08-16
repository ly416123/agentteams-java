package io.agentteams.gateway;

import io.agentteams.contracts.v1.ServerMessage;
import java.util.Objects;

/** Durable command returned with its per-Agent delivery sequence. */
public record SequencedCommand(long sequence, ServerMessage message) {

    public SequencedCommand {
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(message, "message");
    }
}
