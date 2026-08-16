package io.agentteams.gateway;

import io.agentteams.contracts.v1.ServerMessage;
import java.util.List;
import java.util.UUID;

/** Durable command/event-store seam. Implementations allocate ordered per-Agent sequences transactionally. */
public interface CommandReplayPort {

    SequencedCommand append(String agentId, ServerMessage command);

    List<SequencedCommand> replayUnacknowledged(String agentId);

    /** Records a command only after the stream observer accepted the actual send. */
    void markDelivered(String agentId, UUID connectionId, long sequence);

    /** Validates both connection ownership and actual durable delivery of this sequence. */
    AcknowledgementValidation validateAcknowledgement(String agentId, UUID connectionId, long sequence);

    void acknowledge(String agentId, long sequence);

    /** Reads the durable acknowledgement cursor used to initialize a reconnecting registry entry. */
    default long lastAcknowledgedSequence(String agentId) {
        return 0;
    }
}
