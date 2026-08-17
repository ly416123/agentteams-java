package io.agentteams.controlplane.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.ExecutionEventEnvelope;
import io.agentteams.application.api.ExecutionEventPort;
import io.agentteams.application.api.PlatformEventSubjects;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PushSubscribeOptions;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Consumes Agent execution events and applies them through the application boundary. */
public final class NatsExecutionEventConsumer implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(NatsExecutionEventConsumer.class.getName());
    private static final Duration RECEIVE_TIMEOUT = Duration.ofMillis(500);

    private final JetStream jetStream;
    private final ExecutionEventPort executionEvents;
    private final ObjectMapper mapper;
    private final String durable;
    private final AtomicBoolean running = new AtomicBoolean();
    private JetStreamSubscription subscription;
    private ExecutorService executor;

    public NatsExecutionEventConsumer(JetStream jetStream, ExecutionEventPort executionEvents,
            ObjectMapper mapper, String durable) {
        this.jetStream = Objects.requireNonNull(jetStream, "jetStream");
        this.executionEvents = Objects.requireNonNull(executionEvents, "executionEvents");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.durable = durable == null || durable.isBlank() ? "control-plane-execution-events" : durable;
    }

    public synchronized void start() throws IOException, JetStreamApiException {
        if (running.get()) {
            return;
        }
        subscription = jetStream.subscribe(PlatformEventSubjects.AGENT_EXECUTION_EVENTS, durable,
                PushSubscribeOptions.builder().durable(durable).build());
        running.set(true);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "control-plane-agent-events");
            thread.setDaemon(true);
            return thread;
        });
        executor.execute(this::consumeLoop);
    }

    @Override
    public synchronized void close() {
        running.set(false);
        if (subscription != null) {
            subscription.unsubscribe();
            subscription = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                Message message = subscription.nextMessage(RECEIVE_TIMEOUT);
                if (message != null) {
                    process(message);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException error) {
                LOGGER.log(Level.WARNING, "Agent execution event rejected and will be redelivered", error);
            }
        }
    }

    void process(Message message) {
        try {
            ExecutionEventEnvelope envelope = mapper.readValue(message.getData(), ExecutionEventEnvelope.class);
            if ("TASK".equals(envelope.type())) {
                executionEvents.apply(envelope.taskId(), envelope.taskExecution(), envelope.artifacts());
            } else if ("LEASE_RENEWAL".equals(envelope.type())) {
                executionEvents.renewLease(envelope.taskId(), envelope.leaseRenewal());
            } else {
                throw new IllegalArgumentException("unsupported execution event type: " + envelope.type());
            }
            message.ack();
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("invalid Agent execution event", error);
        }
    }
}
