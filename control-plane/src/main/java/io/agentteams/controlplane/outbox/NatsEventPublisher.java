package io.agentteams.controlplane.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.persistence.OutboxEventRecord;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;
import java.io.IOException;
import java.util.Objects;

public final class NatsEventPublisher implements EventPublisher {

    private final JetStreamTransport transport;
    private final ObjectMapper objectMapper;

    public NatsEventPublisher(JetStreamTransport transport, ObjectMapper objectMapper) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public NatsEventPublisher(JetStream jetStream, ObjectMapper objectMapper) {
        this((subject, payload, messageId) -> publishToJetStream(jetStream, subject, payload, messageId), objectMapper);
    }

    @Override
    public void publish(OutboxEventRecord event, String subject) throws Exception {
        publishEnvelope(event, subject, objectMapper.readTree(event.payloadJson()));
    }

    @Override
    public void publishDeadLetter(OutboxEventRecord event, String subject) throws Exception {
        JsonNode redactedPayload = objectMapper.createObjectNode()
                .put("redacted", true)
                .put("reason", "outbox_publish_failed");
        publishEnvelope(event, subject, redactedPayload);
    }

    private void publishEnvelope(OutboxEventRecord event, String subject, JsonNode payload) throws Exception {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(subject, "subject");
        EventEnvelope envelope = new EventEnvelope(event.eventId(), event.eventType(), event.aggregateType(),
                event.aggregateId(), event.aggregateVersion(), event.occurredAt(), payload);
        transport.publish(subject, objectMapper.writeValueAsBytes(envelope), event.eventId().toString());
    }

    private static void publishToJetStream(JetStream jetStream, String subject, byte[] payload, String messageId)
            throws IOException, JetStreamApiException {
        PublishAck acknowledgement = Objects.requireNonNull(jetStream, "jetStream").publish(subject, payload,
                PublishOptions.builder().messageId(messageId).build());
        if (acknowledgement == null) {
            throw new IllegalStateException("JetStream publish returned no acknowledgement");
        }
    }
}
