package io.agentteams.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PushSubscribeOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.agentteams.application.api.TraceContext;

/** Consumes Control Plane Outbox envelopes and turns them into durable gateway commands. */
public final class NatsGatewayEventConsumer implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(NatsGatewayEventConsumer.class.getName());
    private static final Duration RECEIVE_TIMEOUT = Duration.ofMillis(500);

    private final JetStream jetStream;
    private final TaskAssignedCommandHandler commandHandler;
    private final ConfigChangedCommandHandler configHandler;
    private final ObjectMapper objectMapper;
    private final String subject;
    private final String durable;
    private final String configSubject;
    private final String configDurable;
    private final GatewayMetricsPort metrics;
    private final AsyncConsumerTracing tracing;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Object lifecycleMonitor = new Object();
    private JetStreamSubscription subscription;
    private JetStreamSubscription configSubscription;
    private ExecutorService executor;

    public NatsGatewayEventConsumer(JetStream jetStream, TaskAssignedCommandHandler commandHandler,
            ObjectMapper objectMapper, String subject, String durable) {
        this(jetStream, commandHandler, new ConfigChangedCommandHandler(
                commandHandler.delivery(), objectMapper), objectMapper, subject, durable,
                "agent.events.*", "agent-gateway-config", GatewayMetricsPort.noop());
    }

    public NatsGatewayEventConsumer(JetStream jetStream, TaskAssignedCommandHandler commandHandler,
            ConfigChangedCommandHandler configHandler, ObjectMapper objectMapper, String subject, String durable) {
        this(jetStream, commandHandler, configHandler, objectMapper, subject, durable,
                "agent.events.*", "agent-gateway-config", GatewayMetricsPort.noop());
    }

    public NatsGatewayEventConsumer(JetStream jetStream, TaskAssignedCommandHandler commandHandler,
            ConfigChangedCommandHandler configHandler, ObjectMapper objectMapper, String subject, String durable,
            String configSubject, String configDurable) {
        this(jetStream, commandHandler, configHandler, objectMapper, subject, durable, configSubject, configDurable,
                GatewayMetricsPort.noop(), AsyncConsumerTracing.noop());
    }

    public NatsGatewayEventConsumer(JetStream jetStream, TaskAssignedCommandHandler commandHandler,
            ConfigChangedCommandHandler configHandler, ObjectMapper objectMapper, String subject, String durable,
            String configSubject, String configDurable, GatewayMetricsPort metrics) {
        this(jetStream, commandHandler, configHandler, objectMapper, subject, durable, configSubject, configDurable,
                metrics, AsyncConsumerTracing.noop());
    }

    public NatsGatewayEventConsumer(JetStream jetStream, TaskAssignedCommandHandler commandHandler,
            ConfigChangedCommandHandler configHandler, ObjectMapper objectMapper, String subject, String durable,
            String configSubject, String configDurable, GatewayMetricsPort metrics, AsyncConsumerTracing tracing) {
        this.jetStream = Objects.requireNonNull(jetStream, "jetStream");
        this.commandHandler = Objects.requireNonNull(commandHandler, "commandHandler");
        this.configHandler = Objects.requireNonNull(configHandler, "configHandler");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.subject = requireText(subject, "subject");
        this.durable = requireText(durable, "durable");
        this.configSubject = requireText(configSubject, "configSubject");
        this.configDurable = requireText(configDurable, "configDurable");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.tracing = Objects.requireNonNull(tracing, "tracing");
    }

    /** Testable constructor for envelope processing without starting a NATS subscription. */
    public NatsGatewayEventConsumer(TaskAssignedCommandHandler commandHandler, ObjectMapper objectMapper) {
        this(commandHandler, objectMapper, AsyncConsumerTracing.noop());
    }

    NatsGatewayEventConsumer(TaskAssignedCommandHandler commandHandler, ObjectMapper objectMapper,
            AsyncConsumerTracing tracing) {
        this.jetStream = null;
        this.commandHandler = Objects.requireNonNull(commandHandler, "commandHandler");
        this.configHandler = new ConfigChangedCommandHandler(commandHandler.delivery(), objectMapper);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.subject = null;
        this.durable = null;
        this.configSubject = null;
        this.configDurable = null;
        this.metrics = GatewayMetricsPort.noop();
        this.tracing = Objects.requireNonNull(tracing, "tracing");
    }

    public void start() throws IOException, JetStreamApiException {
        synchronized (lifecycleMonitor) {
            if (running.get()) {
                return;
            }
            if (jetStream == null) {
                throw new IllegalStateException("NATS runtime is not configured");
            }
            subscription = jetStream.subscribe(subject, durable,
                    PushSubscribeOptions.builder().durable(durable).build());
            configSubscription = jetStream.subscribe(configSubject, configDurable,
                    PushSubscribeOptions.builder().durable(configDurable).build());
            running.set(true);
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "agent-gateway-nats-consumer");
                thread.setDaemon(true);
                return thread;
            });
            executor.execute(this::consumeLoop);
        }
    }

    /** Parses, handles, and ACKs one message. Invalid messages are deliberately left unacked. */
    public boolean process(Message message) {
        Objects.requireNonNull(message, "message");
        AsyncConsumerTracing.Scope span = null;
        try {
            GatewayOutboxEvent event = parse(message.getData());
            span = tracing.start("agentteams.nats.gateway.consume", event.context())
                    .tag("agentteams.event.type", event.eventType());
            boolean handled = commandHandler.handle(event.eventType(), event.aggregateId().toString(),
                    event.payload().toString(), event.occurredAt(), event.context());
            if (!handled) {
                handled = configHandler.handle(event.eventType(), event.aggregateId().toString(),
                        event.payload().toString(), event.occurredAt(), event.context());
            }
            message.ack();
            metrics.natsEventProcessed();
            span.tag("agentteams.consumer.result", "ack");
            return handled;
        } catch (RuntimeException error) {
            if (span != null) {
                span.error(error).tag("agentteams.consumer.result", "redeliver");
            }
            throw error;
        } finally {
            if (span != null) {
                span.close();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    public void stop() {
        synchronized (lifecycleMonitor) {
            if (!running.getAndSet(false)) {
                return;
            }
            if (subscription != null) {
                subscription.unsubscribe();
                subscription = null;
            }
            if (configSubscription != null) {
                configSubscription.unsubscribe();
                configSubscription = null;
            }
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                Message message = subscription.nextMessage(RECEIVE_TIMEOUT);
                if (message != null) {
                    process(message);
                }
                Message configMessage = configSubscription.nextMessage(RECEIVE_TIMEOUT);
                if (configMessage != null) {
                    processConfig(configMessage);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException error) {
                metrics.natsEventRejected();
                metrics.natsConsumerError();
                LOGGER.log(Level.WARNING, "Agent Gateway NATS event was rejected and will be redelivered", error);
            }
        }
    }

    /** The agent event stream also carries worker-to-control-plane events; consume those without redelivery. */
    private void processConfig(Message message) {
        try {
            process(message);
        } catch (IllegalArgumentException ignored) {
            metrics.natsEventRejected();
            message.ack();
        }
    }

    private GatewayOutboxEvent parse(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("NATS event payload must not be empty");
        }
        try {
            JsonNode root = objectMapper.readTree(new String(data, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("NATS event payload must be a JSON object");
            }
            UUID eventId = uuid(root, "event_id");
            String eventType = text(root, "event_type");
            String aggregateType = text(root, "aggregate_type");
            UUID aggregateId = uuid(root, "aggregate_id");
            JsonNode version = root.get("aggregate_version");
            if (version == null || !version.canConvertToLong() || version.asLong() < 0) {
                throw new IllegalArgumentException("aggregate_version must be a non-negative integer");
            }
            Instant occurredAt;
            try {
                occurredAt = Instant.parse(text(root, "occurred_at"));
            } catch (java.time.DateTimeException error) {
                throw new IllegalArgumentException("occurred_at must be an ISO-8601 instant", error);
            }
            JsonNode payload = root.get("payload");
            if (payload == null || !payload.isObject()) {
                throw new IllegalArgumentException("payload must be a JSON object");
            }
            return new GatewayOutboxEvent(eventId, eventType, aggregateType, aggregateId, version.asLong(),
                    occurredAt, payload, new TraceContext(optionalText(root, "correlation_id", "unknown"),
                            optionalText(root, "traceparent", ""), optionalText(root, "tracestate", "")));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("NATS event payload is invalid JSON", error);
        }
    }

    private static UUID uuid(JsonNode root, String field) {
        String value = text(root, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(field + " must be a UUID", error);
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode root, String field, String fallback) {
        JsonNode value = root.get(field);
        return value == null ? fallback : text(root, field);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private record GatewayOutboxEvent(UUID eventId, String eventType, String aggregateType, UUID aggregateId,
            long aggregateVersion, Instant occurredAt, JsonNode payload, TraceContext context) {
    }
}
