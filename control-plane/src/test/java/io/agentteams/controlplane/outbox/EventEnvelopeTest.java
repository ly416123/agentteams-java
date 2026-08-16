package io.agentteams.controlplane.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    @Test
    void serializesTheStableSnakeCaseEventContract() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(eventId, "TaskCreated", "task", aggregateId, 7,
                Instant.parse("2026-08-16T00:00:00Z"), JsonNodeFactory.instance.objectNode().put("id", "x"));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(envelope);

        assertThat(json).contains("\"event_id\":\"" + eventId + "\"")
                .contains("\"event_type\":\"TaskCreated\"")
                .contains("\"aggregate_type\":\"task\"")
                .contains("\"aggregate_id\":\"" + aggregateId + "\"")
                .contains("\"aggregate_version\":7")
                .contains("\"occurred_at\":\"2026-08-16T00:00:00Z\"")
                .contains("\"payload\":{\"id\":\"x\"}");
    }
}
