package io.agentteams.controlplane.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.JetStream;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;
import io.agentteams.controlplane.persistence.OutboxEventRecord;
import io.agentteams.observability.AsyncProducerTracing;
import io.agentteams.application.api.TraceContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NatsEventPublisherTest {

    @Test
    void publishesEnvelopeWithOutboxEventIdAsMessageId() throws Exception {
        RecordingJetStreamTransport transport = new RecordingJetStreamTransport();
        NatsEventPublisher publisher = new NatsEventPublisher(transport,
                new ObjectMapper().findAndRegisterModules());
        OutboxEventRecord event = event();

        publisher.publish(event);

        assertThat(transport.subject).isEqualTo("task.events." + event.aggregateId());
        assertThat(transport.messageId).isEqualTo(event.eventId().toString());
        JsonNode body = new ObjectMapper().readTree(transport.payload);
        assertThat(body.get("event_id").asText()).isEqualTo(event.eventId().toString());
        assertThat(body.get("aggregate_version").asLong()).isEqualTo(3);
        assertThat(body.get("payload").get("title").asText()).isEqualTo("safe");
    }

    @Test
    void publishesPersistedAsyncTraceContextWithoutChangingThePayloadContract() throws Exception {
        RecordingJetStreamTransport transport = new RecordingJetStreamTransport();
        NatsEventPublisher publisher = new NatsEventPublisher(transport,
                new ObjectMapper().findAndRegisterModules());
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        OutboxEventRecord event = OutboxEventRecord.pending(UUID.randomUUID(), "task", UUID.randomUUID(),
                "TaskCreated", "{\"title\":\"safe\"}", 3, now, now,
                new TraceContext("request-7",
                        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "vendor=value"));

        publisher.publish(event);

        JsonNode body = new ObjectMapper().readTree(transport.payload);
        assertThat(body.get("correlation_id").asText()).isEqualTo("request-7");
        assertThat(body.get("traceparent").asText())
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(body.get("tracestate").asText()).isEqualTo("vendor=value");
    }

    @Test
    void injectsProducerSpanContextIntoPublishedEnvelope() throws Exception {
        RecordingJetStreamTransport transport = new RecordingJetStreamTransport();
        Tracer tracer = mock(Tracer.class);
        Propagator propagator = mock(Propagator.class);
        Span.Builder builder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        io.micrometer.tracing.TraceContext spanContext = mock(io.micrometer.tracing.TraceContext.class);
        when(builder.name(anyString())).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        when(propagator.extract(any(), any())).thenReturn(builder);
        when(tracer.withSpan(span)).thenReturn(scope);
        when(span.context()).thenReturn(spanContext);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> carrier = invocation.getArgument(1);
            @SuppressWarnings("unchecked")
            Propagator.Setter<java.util.Map<String, String>> setter = invocation.getArgument(2);
            setter.set(carrier, "traceparent",
                    "00-4bf92f3577b34da6a3ce929d0e0e4736-1111111111111111-01");
            setter.set(carrier, "tracestate", "producer=value");
            return null;
        }).when(propagator).inject(eq(spanContext), any(), any());
        NatsEventPublisher publisher = new NatsEventPublisher(transport,
                new ObjectMapper().findAndRegisterModules(), new AsyncProducerTracing(tracer, propagator));
        OutboxEventRecord event = OutboxEventRecord.pending(UUID.randomUUID(), "task", UUID.randomUUID(),
                "TaskCreated", "{\"title\":\"safe\"}", 3, Instant.parse("2026-08-16T00:00:00Z"),
                Instant.parse("2026-08-16T00:00:00Z"), new TraceContext("request-7",
                        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "vendor=value"));

        publisher.publish(event);

        JsonNode body = new ObjectMapper().readTree(transport.payload);
        assertThat(body.get("traceparent").asText())
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-1111111111111111-01");
        assertThat(body.get("tracestate").asText()).isEqualTo("producer=value");
        verify(scope).close();
        verify(span).end();
    }

    @Test
    void propagatesPublishFailureWithoutPretendingItWasAcknowledged() {
        RecordingJetStreamTransport transport = new RecordingJetStreamTransport();
        transport.failure = new IllegalStateException("not acknowledged");
        NatsEventPublisher publisher = new NatsEventPublisher(transport,
                new ObjectMapper().findAndRegisterModules());

        assertThatThrownBy(() -> publisher.publish(event()))
                .isSameAs(transport.failure);
    }

    @Test
    void publishesThroughJetStreamWhenPublishReturnsAcknowledgement() throws Exception {
        JetStream jetStream = mock(JetStream.class);
        PublishAck acknowledgement = mock(PublishAck.class);
        when(jetStream.publish(anyString(), any(byte[].class), any(PublishOptions.class)))
                .thenReturn(acknowledgement);
        NatsEventPublisher publisher = new NatsEventPublisher(jetStream,
                new ObjectMapper().findAndRegisterModules());
        OutboxEventRecord event = event();

        publisher.publish(event, "task.events." + event.aggregateId());

        verify(jetStream).publish(eq("task.events." + event.aggregateId()), any(byte[].class),
                any(PublishOptions.class));
    }

    @Test
    void rejectsJetStreamPublishWhenAcknowledgementIsNull() throws Exception {
        JetStream jetStream = mock(JetStream.class);
        when(jetStream.publish(anyString(), any(byte[].class), any(PublishOptions.class)))
                .thenReturn(null);
        NatsEventPublisher publisher = new NatsEventPublisher(jetStream,
                new ObjectMapper().findAndRegisterModules());

        assertThatThrownBy(() -> publisher.publish(event(), "task.events.test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JetStream publish returned no acknowledgement");
    }

    @Test
    void publishesRedactedPayloadToDeadLetterSubject() throws Exception {
        RecordingJetStreamTransport transport = new RecordingJetStreamTransport();
        NatsEventPublisher publisher = new NatsEventPublisher(transport,
                new ObjectMapper().findAndRegisterModules());

        publisher.publishDeadLetter(event(), EventSubjects.DEADLETTER_EVENTS);

        assertThat(transport.subject).isEqualTo(EventSubjects.DEADLETTER_EVENTS);
        assertThat(new ObjectMapper().readTree(transport.payload).get("payload").toString())
                .doesNotContain("safe", "secret")
                .contains("redacted");
    }

    private static OutboxEventRecord event() {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        return OutboxEventRecord.pending(UUID.randomUUID(), "task", UUID.randomUUID(), "TaskCreated",
                "{\"title\":\"safe\"}", 3, now, now);
    }

    private static final class RecordingJetStreamTransport implements JetStreamTransport {
        private String subject;
        private String messageId;
        private byte[] payload;
        private RuntimeException failure;

        @Override
        public void publish(String subject, byte[] payload, String messageId) {
            if (failure != null) {
                throw failure;
            }
            this.subject = subject;
            this.payload = payload;
            this.messageId = messageId;
        }
    }
}
