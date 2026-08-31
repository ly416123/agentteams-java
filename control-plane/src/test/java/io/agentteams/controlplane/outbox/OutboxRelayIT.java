package io.agentteams.controlplane.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.OutboxEventRecord;
import io.agentteams.controlplane.observability.AsyncProducerTracing;
import io.agentteams.application.api.TraceContext;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@ExtendWith(SpringExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class OutboxRelayIT {

    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.10-alpine")
            .withCommand("-js")
            .withExposedPorts(4222);

    private FoundationPersistenceService persistence;
    private Connection connection;
    private JetStream jetStream;

    @BeforeEach
    void setUp() throws Exception {
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false).load().clean();
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        persistence = new FoundationPersistenceService(dataSource);
        connection = Nats.connect("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222));
        JetStreamManagement management = connection.jetStreamManagement();
        try {
            management.deleteStream("AGENT_EVENTS");
        } catch (Exception ignored) {
            // The stream is absent on the first test invocation.
        }
        management.addStream(StreamConfiguration.builder()
                .name("AGENT_EVENTS")
                .subjects("agent.events.>", "task.events.>", "control.events", "deadletter.events")
                .storageType(StorageType.Memory)
                .build());
        jetStream = connection.jetStream();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void publishesAndMarksOutboxEventOnlyAfterJetStreamAcknowledgement() {
        OutboxEventRecord event = insert(event("TaskCreated", 0));
        OutboxRelay relay = relay(new NatsEventPublisher(jetStream, mapper()), Clock.fixed(NOW, ZoneOffset.UTC));

        try (relay) {
            assertThat(relay.relayOnce()).isEqualTo(1);
        }

        assertThat(find(event).status()).isEqualTo("PUBLISHED");
        assertThat(streamMessageCount()).isEqualTo(1);
    }

    @Test
    void duplicateEventIdIsDeduplicatedByJetStream() throws Exception {
        OutboxEventRecord event = event("TaskCreated", 0);
        NatsEventPublisher publisher = new NatsEventPublisher(jetStream, mapper());

        publisher.publish(event);
        publisher.publish(event);

        assertThat(streamMessageCount()).isEqualTo(1);
    }

    @Test
    void carriesInjectedProducerContextThroughJetStream() throws Exception {
        OutboxEventRecord event = event("TaskCreated", 0, new TraceContext("request-7",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "vendor=value"));
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
        }).when(propagator).inject(org.mockito.ArgumentMatchers.eq(spanContext), any(), any());

        new NatsEventPublisher(jetStream, mapper(), new AsyncProducerTracing(tracer, propagator))
                .publish(event);
        JetStreamSubscription subscription = jetStream.subscribe("task.events." + event.aggregateId());
        try {
            Message message = subscription.nextMessage(Duration.ofSeconds(2));
            assertThat(message).isNotNull();
            JsonNode body = mapper().readTree(message.getData());
            assertThat(body.get("traceparent").asText())
                    .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-1111111111111111-01");
            assertThat(body.get("tracestate").asText()).isEqualTo("producer=value");
        } finally {
            subscription.unsubscribe();
        }
    }

    @Test
    void transientPublishFailureIsRetriedFromPersistedPendingState() {
        OutboxEventRecord event = insert(event("TaskCreated", 0));
        NatsEventPublisher delegate = new NatsEventPublisher(jetStream, mapper());
        EventPublisher flaky = new EventPublisher() {
            private boolean failed;

            @Override
            public void publish(OutboxEventRecord value, String subject) throws Exception {
                if (!failed) {
                    failed = true;
                    throw new IllegalStateException("temporary failure");
                }
                delegate.publish(value, subject);
            }

            @Override
            public void publishDeadLetter(OutboxEventRecord value, String subject) throws Exception {
                delegate.publishDeadLetter(value, subject);
            }
        };

        OutboxRelay first = relay(flaky, Clock.fixed(NOW, ZoneOffset.UTC));
        try (first) {
            first.relayOnce();
        }
        assertThat(find(event).status()).isEqualTo("PENDING");

        OutboxRelay second = relay(flaky, Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC));
        try (second) {
            second.relayOnce();
        }
        assertThat(find(event).status()).isEqualTo("PUBLISHED");
    }

    @Test
    void tenthFailedAttemptPublishesRedactedDeadLetterAndPersistsDeadLetterState() {
        OutboxEventRecord event = insert(event("TaskCreated", 9));
        EventPublisher alwaysFail = new EventPublisher() {
            @Override
            public void publish(OutboxEventRecord value, String subject) {
                throw new IllegalStateException("description and token=secret");
            }

            @Override
            public void publishDeadLetter(OutboxEventRecord value, String subject) throws Exception {
                new NatsEventPublisher(jetStream, mapper()).publishDeadLetter(value, subject);
            }
        };

        OutboxRelay relay = relay(alwaysFail, Clock.fixed(NOW, ZoneOffset.UTC));
        try (relay) {
            relay.relayOnce();
        }

        assertThat(find(event).status()).isEqualTo("DEAD_LETTER");
        assertThat(streamMessageCount()).isEqualTo(1);
    }

    private OutboxRelay relay(EventPublisher publisher, Clock clock) {
        OutboxRelayProperties properties = new OutboxRelayProperties();
        properties.setConcurrency(2);
        properties.setBatchSize(2);
        properties.setBaseRetryDelay(java.time.Duration.ofSeconds(1));
        properties.setMaxRetryDelay(java.time.Duration.ofSeconds(4));
        return new OutboxRelay(new JdbcOutboxStore(persistence), publisher, properties, clock);
    }

    private OutboxEventRecord insert(OutboxEventRecord event) {
        persistence.inTransaction(tx -> {
            tx.outboxEvents().insert(event);
            return null;
        });
        return event;
    }

    private OutboxEventRecord find(OutboxEventRecord event) {
        return persistence.inTransaction(tx -> tx.outboxEvents().findByEventId(event.eventId()).orElseThrow());
    }

    private long streamMessageCount() throws RuntimeException {
        try {
            return connection.jetStreamManagement().getStreamInfo("AGENT_EVENTS").getStreamState().getMsgCount();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static OutboxEventRecord event(String eventType, int attempts) {
        return event(eventType, attempts, TraceContext.empty());
    }

    private static OutboxEventRecord event(String eventType, int attempts, TraceContext context) {
        return OutboxEventRecord.pending(UUID.randomUUID(), "task", UUID.randomUUID(), eventType,
                "{\"description\":\"task secret\",\"token\":\"credential\"}", 3, NOW, NOW, context)
                .withAttempts(attempts);
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
