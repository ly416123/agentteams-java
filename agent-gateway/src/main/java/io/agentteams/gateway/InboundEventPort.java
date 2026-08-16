package io.agentteams.gateway;

import java.time.Instant;
import java.util.UUID;

/** Durable inbound-event idempotency seam; event IDs must be unique per accepted Agent event. */
public interface InboundEventPort {

    boolean recordIfNew(String eventId, String agentId, UUID connectionId, Instant receivedAt);
}
