package io.agentteams.controlplane.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentteams.application.api.ExecutionEventEnvelope;
import io.agentteams.application.api.ExecutionEventPort;
import io.agentteams.application.api.ConfigAppliedEnvelope;
import io.agentteams.application.api.ConfigEventPort;
import io.agentteams.application.api.TraceContext;
import io.agentteams.observability.AsyncConsumerTracing;
import io.agentteams.application.api.PlatformEventSubjects;
import io.agentteams.domain.task.StaleTaskVersionException;
import io.agentteams.domain.task.IllegalTaskTransitionException;
import io.agentteams.controlplane.security.AuthorizationException;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.impl.NatsJetStreamMetaData;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
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

/** Consumes Agent execution events and applies them through the application boundary. */
public final class NatsExecutionEventConsumer implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(NatsExecutionEventConsumer.class.getName());
    private static final Duration RECEIVE_TIMEOUT = Duration.ofMillis(1);
    private static final Duration IDLE_BACKOFF = Duration.ofMillis(10);
    private static final Duration OUT_OF_ORDER_REDELIVERY_DELAY = Duration.ofMillis(250);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_CONCURRENCY = 8;
    private static final int DEFAULT_MAX_ACK_PENDING = 32;

    private final JetStream jetStream;
    private final Connection connection;
    private final ExecutionEventPort executionEvents;
    private final ConfigEventPort configEvents;
    private final ObjectMapper mapper;
    private final String durable;
    private final AsyncConsumerTracing tracing;
    private final int maxAckPending;
    private final OrderedDispatcher dispatcher;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ConnectionListener connectionListener = this::onConnectionEvent;
    private JetStreamSubscription subscription;
    private ExecutorService executor;

    public NatsExecutionEventConsumer(JetStream jetStream, ExecutionEventPort executionEvents,
            ObjectMapper mapper, String durable) {
        this(null, jetStream, executionEvents, command -> { }, mapper, durable, AsyncConsumerTracing.noop(),
                DEFAULT_CONCURRENCY, DEFAULT_MAX_ACK_PENDING);
    }

    public NatsExecutionEventConsumer(Connection connection, ExecutionEventPort executionEvents,
            ObjectMapper mapper, String durable) throws IOException {
        this(connection, connection.jetStream(), executionEvents, command -> { }, mapper, durable,
                AsyncConsumerTracing.noop(), DEFAULT_CONCURRENCY, DEFAULT_MAX_ACK_PENDING);
    }

    public NatsExecutionEventConsumer(JetStream jetStream, ExecutionEventPort executionEvents,
            ConfigEventPort configEvents, ObjectMapper mapper, String durable) {
        this(null, jetStream, executionEvents, configEvents, mapper, durable, AsyncConsumerTracing.noop(),
                DEFAULT_CONCURRENCY, DEFAULT_MAX_ACK_PENDING);
    }

    public NatsExecutionEventConsumer(Connection connection, ExecutionEventPort executionEvents,
            ConfigEventPort configEvents, ObjectMapper mapper, String durable) throws IOException {
        this(connection, connection.jetStream(), executionEvents, configEvents, mapper, durable,
                AsyncConsumerTracing.noop(), DEFAULT_CONCURRENCY, DEFAULT_MAX_ACK_PENDING);
    }

    public NatsExecutionEventConsumer(JetStream jetStream, ExecutionEventPort executionEvents,
            ConfigEventPort configEvents, ObjectMapper mapper, String durable, AsyncConsumerTracing tracing) {
        this(null, jetStream, executionEvents, configEvents, mapper, durable, tracing,
                DEFAULT_CONCURRENCY, DEFAULT_MAX_ACK_PENDING);
    }

    public NatsExecutionEventConsumer(Connection connection, ExecutionEventPort executionEvents,
            ConfigEventPort configEvents, ObjectMapper mapper, String durable, AsyncConsumerTracing tracing)
            throws IOException {
        this(connection, connection.jetStream(), executionEvents, configEvents, mapper, durable, tracing,
                DEFAULT_CONCURRENCY, DEFAULT_MAX_ACK_PENDING);
    }

    public NatsExecutionEventConsumer(JetStream jetStream, ExecutionEventPort executionEvents,
            ConfigEventPort configEvents, ObjectMapper mapper, String durable, AsyncConsumerTracing tracing,
            int concurrency, int maxAckPending) {
        this(null, jetStream, executionEvents, configEvents, mapper, durable, tracing,
                concurrency, maxAckPending);
    }

    public NatsExecutionEventConsumer(Connection connection, ExecutionEventPort executionEvents,
            ConfigEventPort configEvents, ObjectMapper mapper, String durable, AsyncConsumerTracing tracing,
            int concurrency, int maxAckPending) throws IOException {
        this(connection, connection.jetStream(), executionEvents, configEvents, mapper, durable, tracing,
                concurrency, maxAckPending);
    }

    private NatsExecutionEventConsumer(Connection connection, JetStream jetStream,
            ExecutionEventPort executionEvents,
            ConfigEventPort configEvents, ObjectMapper mapper, String durable, AsyncConsumerTracing tracing,
            int concurrency, int maxAckPending) {
        this.jetStream = Objects.requireNonNull(jetStream, "jetStream");
        this.connection = connection;
        this.executionEvents = Objects.requireNonNull(executionEvents, "executionEvents");
        this.configEvents = Objects.requireNonNull(configEvents, "configEvents");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.durable = durable == null || durable.isBlank() ? "control-plane-execution-events" : durable;
        this.tracing = Objects.requireNonNull(tracing, "tracing");
        this.maxAckPending = requirePositiveAtLeast(maxAckPending, concurrency, "maxAckPending");
        this.dispatcher = new OrderedDispatcher(concurrency, this.maxAckPending);
    }

    public synchronized void start() throws IOException, JetStreamApiException {
        if (running.get()) {
            return;
        }
        subscription = jetStream.subscribe(PlatformEventSubjects.AGENT_EXECUTION_EVENTS, durable,
                subscribeOptions());
        running.set(true);
        if (connection != null) {
            connection.addConnectionListener(connectionListener);
        }
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "control-plane-agent-events");
            thread.setDaemon(true);
            return thread;
        });
        executor.execute(this::consumeLoop);
    }

    @Override
    public void close() {
        close(DEFAULT_SHUTDOWN_TIMEOUT);
    }

    public synchronized void close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        running.set(false);
        if (connection != null) {
            connection.removeConnectionListener(connectionListener);
        }
        if (subscription != null) {
            subscription.unsubscribe();
            subscription = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        dispatcher.close(timeout);
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                boolean processed = false;
                Message message = subscription.nextMessage(RECEIVE_TIMEOUT);
                if (message != null) {
                    dispatch(message);
                    processed = true;
                }
                if (!processed) {
                    Thread.sleep(IDLE_BACKOFF.toMillis());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException error) {
                LOGGER.log(Level.WARNING, "Agent execution event rejected and will be redelivered", error);
            }
        }
    }

    boolean dispatch(Message message) {
        Objects.requireNonNull(message, "message");
        boolean accepted = dispatcher.submit(orderingKey(message), () -> process(message),
                error -> redeliverAfterDispatchFailure(message, error));
        if (!accepted) {
            redeliverAfterDispatchFailure(message,
                    new RejectedExecutionException("NATS dispatcher is full or closed"));
        }
        return accepted;
    }

    boolean awaitIdle(Duration timeout) throws InterruptedException {
        return dispatcher.awaitIdle(timeout);
    }

    private void redeliverAfterDispatchFailure(Message message, Throwable error) {
        try {
            message.nakWithDelay(OUT_OF_ORDER_REDELIVERY_DELAY);
        } catch (RuntimeException nakFailure) {
            LOGGER.log(Level.WARNING, "Unable to NAK rejected Control Plane NATS execution event", nakFailure);
        }
        LOGGER.log(Level.WARNING, "Agent execution event was rejected and will be redelivered", error);
    }

    private String orderingKey(Message message) {
        try {
            JsonNode root = mapper.readTree(message.getData());
            if (root != null && root.isObject()) {
                for (String field : new String[] {"aggregate_id", "taskId", "task_id", "agentId", "agent_id"}) {
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

    void process(Message message) {
        AsyncConsumerTracing.Scope span = null;
        try {
            JsonNode root = mapper.readTree(message.getData());
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("execution event payload must be a JSON object");
            }
            // Agent aggregate outbox events share the agent.events.* subject. They are
            // intentionally acknowledged here because this consumer only owns execution
            // envelopes and must not block later agent execution messages.
            if (!root.hasNonNull("type") && root.hasNonNull("event_type")
                    && root.hasNonNull("aggregate_type") && root.hasNonNull("aggregate_id")
                    && root.hasNonNull("payload")) {
                span = tracing.start("agentteams.nats.execution.consume", TraceContext.empty())
                        .tag("agentteams.consumer.result", "ignored");
                message.ack();
                return;
            }
            if ("CONFIG_APPLIED".equals(root.path("type").asText())) {
                ConfigAppliedEnvelope envelope = mapper.treeToValue(root, ConfigAppliedEnvelope.class);
                span = tracing.start("agentteams.nats.execution.consume",
                        new TraceContext(envelope.correlationId(), "", ""));
                span.tag("agentteams.event.type", envelope.type()).tag("agentteams.consumer.result", "ack");
                configEvents.applied(new ConfigEventPort.ConfigAppliedCommand(envelope.eventId(),
                        envelope.bindingId(), envelope.snapshotId(), envelope.agentId(), envelope.configVersion(),
                        envelope.applied(), envelope.errorMessage(), envelope.occurredAt(), envelope.source(),
                        envelope.correlationId(), envelope.resourceResults()));
                message.ack();
                return;
            }
            ExecutionEventEnvelope envelope = mapper.treeToValue(root, ExecutionEventEnvelope.class);
            TraceContext context = new TraceContext(envelope.correlationId(), envelope.traceparent(),
                    envelope.tracestate());
            span = tracing.start("agentteams.nats.execution.consume", context);
            span.tag("agentteams.event.type", envelope.type());
            if ("TASK".equals(envelope.type())) {
                executionEvents.apply(envelope.taskId(), withContext(envelope.taskExecution(), envelope), envelope.artifacts());
            } else if ("LEASE_RENEWAL".equals(envelope.type())) {
                executionEvents.renewLease(envelope.taskId(), withContext(envelope.leaseRenewal(), envelope));
            } else if ("REJECTION".equals(envelope.type())) {
                executionEvents.rejectUnaccepted(envelope.taskId(), withContext(envelope.rejection(), envelope));
            } else {
                throw new IllegalArgumentException("unsupported execution event type: " + envelope.type());
            }
            message.ack();
            span.tag("agentteams.consumer.result", "ack");
        } catch (StaleTaskVersionException stale) {
            if (stale.expectedVersion() < stale.actualVersion()) {
                // The aggregate has already advanced beyond this event. Retrying
                // it forever would poison the durable consumer and block newer
                // execution events, so acknowledge the irrecoverably stale event.
                LOGGER.log(Level.FINE, "Acknowledging stale Agent execution event: expected={0}, actual={1}",
                        new Object[] {stale.expectedVersion(), stale.actualVersion()});
                message.ack();
                if (span != null) {
                    span.tag("agentteams.consumer.result", "stale");
                }
            } else {
                // A Gateway can publish consecutive events from different
                // transport callbacks concurrently. If this event is ahead of
                // the aggregate, its predecessor is still in flight and the
                // event must remain unacked so JetStream redelivers it.
                if (span != null) {
                    span.tag("agentteams.consumer.result", "waiting_for_predecessor");
                }
                message.nakWithDelay(outOfOrderRedeliveryDelay(message));
            }
        } catch (IllegalTaskTransitionException terminal) {
            // A terminal task cannot be moved back to an earlier phase. The
            // event is permanently invalid for this aggregate; ACK it so one
            // historical bad event cannot poison the durable consumer.
            LOGGER.log(Level.WARNING, "Acknowledging impossible Agent task transition: {0}", terminal.getMessage());
            message.ack();
        } catch (AuthorizationException unauthorized) {
            // Identity/scope violations are terminal for this envelope. Redelivery
            // would only poison the durable consumer and cannot make an invalid
            // agent/lease pairing valid.
            LOGGER.log(Level.WARNING, "Acknowledging unauthorized Agent execution event: {0}",
                    unauthorized.getMessage());
            message.ack();
            if (span != null) {
                span.tag("agentteams.consumer.result", "unauthorized");
            }
        } catch (IOException | RuntimeException error) {
            if (span != null) {
                span.error(error).tag("agentteams.consumer.result", "redeliver");
            }
            throw new IllegalArgumentException("invalid Agent execution event", error);
        } finally {
            if (span != null) {
                span.close();
            }
        }
    }

    private void onConnectionEvent(Connection ignored, ConnectionListener.Events event) {
        if (event != ConnectionListener.Events.RESUBSCRIBED || !running.get()) {
            return;
        }
        synchronized (this) {
            if (!running.get()) {
                return;
            }
            unsubscribe(subscription);
            try {
                subscription = jetStream.subscribe(PlatformEventSubjects.AGENT_EXECUTION_EVENTS, durable,
                        subscribeOptions());
                LOGGER.info("Control Plane NATS execution subscription restored after reconnect");
            } catch (IOException | JetStreamApiException error) {
                LOGGER.log(Level.WARNING,
                        "Unable to restore Control Plane NATS execution subscription after reconnect", error);
            }
        }
    }

    private static void unsubscribe(JetStreamSubscription candidate) {
        if (candidate != null) {
            candidate.unsubscribe();
        }
    }

    private PushSubscribeOptions subscribeOptions() {
        return PushSubscribeOptions.builder().durable(durable)
                .configuration(io.nats.client.api.ConsumerConfiguration.builder()
                        .durable(durable)
                        .maxAckPending(maxAckPending)
                        .build())
                .build();
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
                        Thread thread = new Thread(runnable, "control-plane-nats-dispatcher");
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
                    LOGGER.warning("Control Plane NATS dispatcher shutdown timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.WARNING, "Control Plane NATS dispatcher shutdown interrupted", interrupted);
            }
        }
    }

    private static io.agentteams.application.api.ExecutionEventPort.TaskExecutionCommand withContext(
            io.agentteams.application.api.ExecutionEventPort.TaskExecutionCommand command,
            ExecutionEventEnvelope envelope) {
        TraceContext context = new TraceContext(envelope.correlationId(), envelope.traceparent(), envelope.tracestate());
        return new io.agentteams.application.api.ExecutionEventPort.TaskExecutionCommand(command.eventId(),
                command.expectedVersion(), command.attemptId(), command.leaseId(), command.occurredAt(),
                command.agentId(), command.source(), command.phase(), command.failureCode(), command.failureMessage(),
                context.correlationId(), context.traceparent(), context.tracestate());
    }

    private static io.agentteams.application.api.ExecutionEventPort.LeaseRenewalCommand withContext(
            io.agentteams.application.api.ExecutionEventPort.LeaseRenewalCommand command,
            ExecutionEventEnvelope envelope) {
        TraceContext context = new TraceContext(envelope.correlationId(), envelope.traceparent(), envelope.tracestate());
        return new io.agentteams.application.api.ExecutionEventPort.LeaseRenewalCommand(command.eventId(),
                command.expectedVersion(), command.attemptId(), command.leaseId(), command.occurredAt(),
                command.requestedExpiry(), command.agentId(), command.source(), context.correlationId(),
                context.traceparent(), context.tracestate());
    }

    private static io.agentteams.application.api.ExecutionEventPort.RejectionCommand withContext(
            io.agentteams.application.api.ExecutionEventPort.RejectionCommand command,
            ExecutionEventEnvelope envelope) {
        TraceContext context = new TraceContext(envelope.correlationId(), envelope.traceparent(), envelope.tracestate());
        return new io.agentteams.application.api.ExecutionEventPort.RejectionCommand(command.eventId(),
                command.expectedVersion(), command.attemptId(), command.leaseId(), command.occurredAt(),
                command.agentId(), command.source(), command.rejectionReason(), context.correlationId(),
                context.traceparent(), context.tracestate());
    }

    private static Duration outOfOrderRedeliveryDelay(Message message) {
        NatsJetStreamMetaData metadata = message.metaData();
        long delivered = metadata == null ? 1 : Math.max(1, metadata.deliveredCount());
        int exponent = (int) Math.min(5, delivered - 1);
        return OUT_OF_ORDER_REDELIVERY_DELAY.multipliedBy(1L << exponent);
    }
}
