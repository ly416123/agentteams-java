package io.agentteams.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.api.ConsumerConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.agentteams.application.api.TraceContext;

/** Consumes Control Plane Outbox envelopes and turns them into durable gateway commands. */
public final class NatsGatewayEventConsumer implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(NatsGatewayEventConsumer.class.getName());
    private static final Duration RECEIVE_TIMEOUT = Duration.ofMillis(1);
    private static final Duration IDLE_BACKOFF = Duration.ofMillis(10);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_CONCURRENCY = 8;
    private static final int DEFAULT_MAX_ACK_PENDING = 32;

    private final JetStream jetStream;
    private final Connection connection;
    private final TaskAssignedCommandHandler commandHandler;
    private final ConfigChangedCommandHandler configHandler;
    private final ObjectMapper objectMapper;
    private final String subject;
    private final String durable;
    private final String configSubject;
    private final String configDurable;
    private final GatewayMetricsPort metrics;
    private final AsyncConsumerTracing tracing;
    private final int maxAckPending;
    private final OrderedDispatcher dispatcher;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Object lifecycleMonitor = new Object();
    private final ConnectionListener connectionListener = this::onConnectionEvent;
    private JetStreamSubscription subscription;
    private JetStreamSubscription configSubscription;
    private ExecutorService executor;

    public NatsGatewayEventConsumer(JetStream jetStream, TaskAssignedCommandHandler commandHandler,
            ObjectMapper objectMapper, String subject, String durable) {
        this((Connection) null, jetStream, commandHandler, new ConfigChangedCommandHandler(
                commandHandler.delivery(), objectMapper), objectMapper, subject, durable,
                "agent.events.*", "agent-gateway-config", GatewayMetricsPort.noop(), AsyncConsumerTracing.noop(),
                DEFAULT_CONCURRENCY, DEFAULT_MAX_ACK_PENDING);
    }

    public NatsGatewayEventConsumer(Connection connection, TaskAssignedCommandHandler commandHandler,
            ObjectMapper objectMapper, String subject, String durable) throws IOException {
        this(connection, connection.jetStream(), commandHandler, new ConfigChangedCommandHandler(
                commandHandler.delivery(), objectMapper), objectMapper, subject, durable,
                "agent.events.*", "agent-gateway-config", GatewayMetricsPort.noop(), AsyncConsumerTracing.noop(),
                DEFAULT_CONCURRENCY, DEFAULT_MAX_ACK_PENDING);
    }

    public NatsGatewayEventConsumer(JetStream jetStream, TaskAssignedCommandHandler commandHandler,
            ConfigChangedCommandHandler configHandler, ObjectMapper objectMapper, String subject, String durable) {
        this((Connection) null, jetStream, commandHandler, configHandler, objectMapper, subject, durable,
                "agent.events.*", "agent-gateway-config", GatewayMetricsPort.noop(), AsyncConsumerTracing.noop(),
                DEFAULT_CONCURRENCY, DEFAULT_MAX_ACK_PENDING);
    }

    public NatsGatewayEventConsumer(JetStream jetStream, TaskAssignedCommandHandler commandHandler,
            ConfigChangedCommandHandler configHandler, ObjectMapper objectMapper, String subject, String durable,
            String configSubject, String configDurable) {
        this((Connection) null, jetStream, commandHandler, configHandler, objectMapper, subject, durable, configSubject, configDurable,
                GatewayMetricsPort.noop(), AsyncConsumerTracing.noop(), DEFAULT_CONCURRENCY, DEFAULT_MAX_ACK_PENDING);
    }

    public NatsGatewayEventConsumer(JetStream jetStream, TaskAssignedCommandHandler commandHandler,
            ConfigChangedCommandHandler configHandler, ObjectMapper objectMapper, String subject, String durable,
            String configSubject, String configDurable, GatewayMetricsPort metrics) {
        this((Connection) null, jetStream, commandHandler, configHandler, objectMapper, subject, durable, configSubject, configDurable,
                metrics, AsyncConsumerTracing.noop(), DEFAULT_CONCURRENCY, DEFAULT_MAX_ACK_PENDING);
    }

    public NatsGatewayEventConsumer(Connection connection, TaskAssignedCommandHandler commandHandler,
            ConfigChangedCommandHandler configHandler, ObjectMapper objectMapper, String subject, String durable,
            String configSubject, String configDurable, GatewayMetricsPort metrics,
            AsyncConsumerTracing tracing) throws IOException {
        this(connection, connection.jetStream(), commandHandler, configHandler, objectMapper, subject, durable,
                configSubject, configDurable, metrics, tracing, DEFAULT_CONCURRENCY, DEFAULT_MAX_ACK_PENDING);
    }

    public NatsGatewayEventConsumer(Connection connection, TaskAssignedCommandHandler commandHandler,
            ConfigChangedCommandHandler configHandler, ObjectMapper objectMapper, String subject, String durable,
            String configSubject, String configDurable, GatewayMetricsPort metrics,
            AsyncConsumerTracing tracing, int concurrency, int maxAckPending) throws IOException {
        this(connection, connection.jetStream(), commandHandler, configHandler, objectMapper, subject, durable,
                configSubject, configDurable, metrics, tracing, concurrency, maxAckPending);
    }

    private NatsGatewayEventConsumer(Connection connection, JetStream jetStream,
            TaskAssignedCommandHandler commandHandler,
            ConfigChangedCommandHandler configHandler, ObjectMapper objectMapper, String subject, String durable,
            String configSubject, String configDurable, GatewayMetricsPort metrics, AsyncConsumerTracing tracing,
            int concurrency, int maxAckPending) {
        this.jetStream = Objects.requireNonNull(jetStream, "jetStream");
        this.connection = connection;
        this.commandHandler = Objects.requireNonNull(commandHandler, "commandHandler");
        this.configHandler = Objects.requireNonNull(configHandler, "configHandler");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.subject = requireText(subject, "subject");
        this.durable = requireText(durable, "durable");
        this.configSubject = requireText(configSubject, "configSubject");
        this.configDurable = requireText(configDurable, "configDurable");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.tracing = Objects.requireNonNull(tracing, "tracing");
        this.maxAckPending = requirePositiveAtLeast(maxAckPending, concurrency, "maxAckPending");
        this.dispatcher = new OrderedDispatcher(concurrency, this.maxAckPending);
    }

    /** Testable constructor for envelope processing without starting a NATS subscription. */
    public NatsGatewayEventConsumer(TaskAssignedCommandHandler commandHandler, ObjectMapper objectMapper) {
        this(commandHandler, objectMapper, AsyncConsumerTracing.noop(), DEFAULT_CONCURRENCY,
                DEFAULT_MAX_ACK_PENDING);
    }

    NatsGatewayEventConsumer(TaskAssignedCommandHandler commandHandler, ObjectMapper objectMapper,
            AsyncConsumerTracing tracing) {
        this(commandHandler, objectMapper, tracing, DEFAULT_CONCURRENCY, DEFAULT_MAX_ACK_PENDING);
    }

    NatsGatewayEventConsumer(TaskAssignedCommandHandler commandHandler, ObjectMapper objectMapper,
            AsyncConsumerTracing tracing, int concurrency, int maxAckPending) {
        this(commandHandler, objectMapper, GatewayMetricsPort.noop(), tracing, concurrency, maxAckPending);
    }

    NatsGatewayEventConsumer(TaskAssignedCommandHandler commandHandler, ObjectMapper objectMapper,
            GatewayMetricsPort metrics, AsyncConsumerTracing tracing, int concurrency, int maxAckPending) {
        this.connection = null;
        this.jetStream = null;
        this.commandHandler = Objects.requireNonNull(commandHandler, "commandHandler");
        this.configHandler = new ConfigChangedCommandHandler(commandHandler.delivery(), objectMapper);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.subject = null;
        this.durable = null;
        this.configSubject = null;
        this.configDurable = null;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.tracing = Objects.requireNonNull(tracing, "tracing");
        this.maxAckPending = requirePositiveAtLeast(maxAckPending, concurrency, "maxAckPending");
        this.dispatcher = new OrderedDispatcher(concurrency, this.maxAckPending);
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
                    subscribeOptions(durable));
            configSubscription = jetStream.subscribe(configSubject, configDurable, configSubscribeOptions());
            running.set(true);
            if (connection != null) {
                connection.addConnectionListener(connectionListener);
            }
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
        stop(DEFAULT_SHUTDOWN_TIMEOUT);
    }

    public void close(Duration timeout) {
        stop(timeout);
    }

    private void stop(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        synchronized (lifecycleMonitor) {
            running.set(false);
            if (connection != null) {
                connection.removeConnectionListener(connectionListener);
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
        dispatcher.close(timeout);
    }

    private void onConnectionEvent(Connection ignored, ConnectionListener.Events event) {
        if (event != ConnectionListener.Events.RESUBSCRIBED || !running.get()) {
            return;
        }
        synchronized (lifecycleMonitor) {
            if (!running.get()) {
                return;
            }
            unsubscribe(subscription);
            unsubscribe(configSubscription);
            try {
                subscription = jetStream.subscribe(subject, durable, subscribeOptions(durable));
                configSubscription = jetStream.subscribe(configSubject, configDurable, configSubscribeOptions());
                LOGGER.info("Agent Gateway NATS subscriptions restored after reconnect");
            } catch (IOException | JetStreamApiException error) {
                LOGGER.log(Level.WARNING, "Unable to restore Agent Gateway NATS subscriptions after reconnect", error);
            }
        }
    }

    private static void unsubscribe(JetStreamSubscription candidate) {
        if (candidate != null) {
            candidate.unsubscribe();
        }
    }

    private PushSubscribeOptions configSubscribeOptions() {
        return subscribeOptions(configDurable);
    }

    private PushSubscribeOptions subscribeOptions(String consumerDurable) {
        return PushSubscribeOptions.builder().durable(consumerDurable)
                .configuration(ConsumerConfiguration.builder()
                        .durable(consumerDurable)
                        .maxAckPending(maxAckPending)
                        .build())
                .build();
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                boolean processed = false;
                Message message = subscription.nextMessage(RECEIVE_TIMEOUT);
                if (message != null) {
                    dispatch(message, false);
                    processed = true;
                }
                Message configMessage = configSubscription.nextMessage(RECEIVE_TIMEOUT);
                if (configMessage != null) {
                    dispatch(configMessage, true);
                    processed = true;
                }
                if (!processed) {
                    Thread.sleep(IDLE_BACKOFF.toMillis());
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

    boolean dispatch(Message message) {
        return dispatch(message, false);
    }

    private boolean dispatch(Message message, boolean config) {
        Objects.requireNonNull(message, "message");
        boolean accepted = dispatcher.submit(orderingKey(message),
                () -> {
                    if (config) {
                        processConfig(message);
                    } else {
                        process(message);
                    }
                }, error -> redeliverAfterDispatchFailure(message, error));
        if (!accepted) {
            metrics.natsEventRejected();
            redeliverAfterDispatchFailure(message, new RejectedExecutionException("NATS dispatcher is full or closed"));
        }
        return accepted;
    }

    boolean awaitIdle(Duration timeout) throws InterruptedException {
        return dispatcher.awaitIdle(timeout);
    }

    private void redeliverAfterDispatchFailure(Message message, Throwable error) {
        metrics.natsEventRejected();
        metrics.natsConsumerError();
        try {
            message.nakWithDelay(Duration.ofMillis(250));
        } catch (RuntimeException nakFailure) {
            LOGGER.log(Level.WARNING, "Unable to NAK rejected Agent Gateway NATS event", nakFailure);
        }
        LOGGER.log(Level.WARNING, "Agent Gateway NATS event was rejected and will be redelivered", error);
    }

    private String orderingKey(Message message) {
        try {
            JsonNode root = objectMapper.readTree(message.getData());
            if (root != null && root.isObject()) {
                for (String field : new String[] {"aggregate_id", "agentId", "agent_id", "taskId", "task_id"}) {
                    JsonNode value = root.get(field);
                    if (value != null && value.isTextual() && !value.asText().isBlank()) {
                        return value.asText();
                    }
                }
            }
        } catch (IOException ignored) {
            // Invalid envelopes use one bounded poison-message lane and remain unacked.
        }
        return "__invalid__";
    }

    /** The agent event stream also carries worker-to-control-plane events; consume those without redelivery. */
    void processConfig(Message message) {
        if (isWorkerEvent(message.getData())) {
            metrics.natsEventRejected();
            message.ack();
            return;
        }
        try {
            process(message);
        } catch (IllegalArgumentException ignored) {
            metrics.natsEventRejected();
            message.ack();
        }
    }

    private boolean isWorkerEvent(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(new String(data, StandardCharsets.UTF_8));
            return root != null && root.isObject() && root.has("schemaVersion") && root.has("type")
                    && !root.has("event_type");
        } catch (JsonProcessingException ignored) {
            return false;
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

    private static int requirePositiveAtLeast(int value, int minimum, String field) {
        if (value < 1 || value < minimum) {
            throw new IllegalArgumentException(field + " must be positive and at least concurrency");
        }
        return value;
    }

    private static final class OrderedDispatcher {
        private final ExecutorService workers;
        private final Semaphore capacity;
        private final java.util.Map<String, CompletableFuture<Void>> tails = new java.util.concurrent.ConcurrentHashMap<>();
        private final Object monitor = new Object();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicBoolean accepting = new AtomicBoolean(true);

        private OrderedDispatcher(int concurrency, int maxAckPending) {
            this.capacity = new Semaphore(maxAckPending);
            this.workers = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(maxAckPending), runnable -> {
                        Thread thread = new Thread(runnable, "agent-gateway-nats-dispatcher");
                        thread.setDaemon(true);
                        return thread;
                    });
        }

        private boolean submit(String key, Runnable task, Consumer<Throwable> failureHandler) {
            if (!accepting.get() || !capacity.tryAcquire()) {
                return false;
            }
            inFlight.incrementAndGet();
            CompletableFuture<Void> next;
            try {
                synchronized (tails) {
                    CompletableFuture<Void> previous = tails.get(key);
                    next = (previous == null ? CompletableFuture.completedFuture(null) : previous)
                            .thenRunAsync(task, workers);
                    tails.put(key, next);
                }
            } catch (RejectedExecutionException rejected) {
                capacity.release();
                decrementInFlight();
                return false;
            }
            next.whenComplete((ignored, error) -> {
                try {
                    if (error != null) {
                        failureHandler.accept(error);
                    }
                } finally {
                    synchronized (tails) {
                        if (tails.get(key) == next) {
                            tails.remove(key);
                        }
                    }
                    capacity.release();
                    decrementInFlight();
                }
            });
            return true;
        }

        private void decrementInFlight() {
            if (inFlight.decrementAndGet() == 0) {
                synchronized (monitor) {
                    monitor.notifyAll();
                }
            }
        }

        private boolean awaitIdle(Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            synchronized (monitor) {
                while (inFlight.get() != 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        return false;
                    }
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                }
                return true;
            }
        }

        private void close(Duration timeout) {
            accepting.set(false);
            workers.shutdown();
            try {
                if (!workers.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                    LOGGER.warning("Agent Gateway NATS dispatcher shutdown timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.WARNING, "Agent Gateway NATS dispatcher shutdown interrupted", interrupted);
            }
        }
    }

    private record GatewayOutboxEvent(UUID eventId, String eventType, String aggregateType, UUID aggregateId,
            long aggregateVersion, Instant occurredAt, JsonNode payload, TraceContext context) {
    }
}
