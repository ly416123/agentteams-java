package io.agentteams.controlplane.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.persistence.OutboxEventRecord;
import io.agentteams.observability.AsyncProducerTracing;
import io.agentteams.application.api.TraceContext;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;
import java.io.IOException;
import java.util.Objects;

public final class NatsEventPublisher implements EventPublisher {

    private final JetStreamTransport transport;
    private final ObjectMapper objectMapper;
    private final AsyncProducerTracing tracing;

    public NatsEventPublisher(JetStreamTransport transport, ObjectMapper objectMapper) {
        this(transport, objectMapper, AsyncProducerTracing.noop());
    }

    public NatsEventPublisher(JetStreamTransport transport, ObjectMapper objectMapper,
            AsyncProducerTracing tracing) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.tracing = Objects.requireNonNull(tracing, "tracing");
    }

    public NatsEventPublisher(JetStream jetStream, ObjectMapper objectMapper) {
        this(jetStream, objectMapper, AsyncProducerTracing.noop());
    }

    public NatsEventPublisher(JetStream jetStream, ObjectMapper objectMapper, AsyncProducerTracing tracing) {
        this((subject, payload, messageId) -> publishToJetStream(jetStream, subject, payload, messageId), objectMapper,
                tracing);
    }

    @Override
    public void publish(OutboxEventRecord event, String subject) throws Exception {
        publishEnvelope(event, subject, objectMapper.readTree(event.payloadJson()), "publish");
    }

    @Override
    public void publishDeadLetter(OutboxEventRecord event, String subject) throws Exception {
        JsonNode redactedPayload = objectMapper.createObjectNode()
                .put("redacted", true)
                .put("reason", "outbox_publish_failed");
        publishEnvelope(event, subject, redactedPayload, "dead_letter");
    }

    private void publishEnvelope(OutboxEventRecord event, String subject, JsonNode payload, String delivery) throws Exception {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(subject, "subject");
        TraceContext parent;
        try {
            parent = new TraceContext(event.correlationId(), event.traceparent(), event.tracestate());
        } catch (IllegalArgumentException malformedContext) {
            // Keep publishing legacy rows; the original values remain the envelope fallback.
            parent = TraceContext.empty();
        }
        try (AsyncProducerTracing.Scope span = tracing.start("agentteams.nats.outbox.publish", parent)
                .tag("agentteams.event.type", event.eventType())
                .tag("agentteams.outbox.delivery", delivery)) {
            java.util.Map<String, String> carrier = span.inject(event.traceparent(), event.tracestate());
            EventEnvelope envelope = new EventEnvelope(event.eventId(), event.eventType(), event.aggregateType(),
                    event.aggregateId(), event.aggregateVersion(), event.occurredAt(), payload,
                    event.correlationId(), carrier.getOrDefault("traceparent", event.traceparent()),
                    carrier.getOrDefault("tracestate", event.tracestate()));
            transport.publish(subject, objectMapper.writeValueAsBytes(envelope), event.eventId().toString());
        }
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
