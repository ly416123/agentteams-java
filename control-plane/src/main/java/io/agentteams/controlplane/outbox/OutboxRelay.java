package io.agentteams.controlplane.outbox;

import io.agentteams.controlplane.persistence.OutboxEventRecord;
import io.agentteams.observability.TaskMetricsPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class OutboxRelay implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore store;
    private final EventPublisher publisher;
    private final OutboxRelayProperties properties;
    private final Clock clock;
    private final TaskMetricsPort metrics;
    private final ExecutorService workers;
    private final Object lifecycleMonitor = new Object();
    private final AtomicBoolean closing = new AtomicBoolean();

    public OutboxRelay(OutboxStore store, EventPublisher publisher, OutboxRelayProperties properties, Clock clock) {
        this(store, publisher, properties, clock, TaskMetricsPort.noop());
    }

    public OutboxRelay(OutboxStore store, EventPublisher publisher, OutboxRelayProperties properties, Clock clock,
            TaskMetricsPort metrics) {
        this.store = Objects.requireNonNull(store, "store");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.workers = Executors.newFixedThreadPool(properties.getConcurrency(), daemonThreadFactory());
    }

    @Scheduled(fixedDelayString = "${agentteams.outbox.relay.poll-interval-ms:1000}")
    public void scheduledRelay() {
        relayOnce();
    }

    public int relayOnce() {
        Instant now = clock.instant();
        long pending = store.pendingCount();
        if (pending >= 0) {
            metrics.outboxBacklog(pending);
        }
        metrics.outboxOldestPendingAge(store.oldestPendingAt()
                .map(oldest -> Duration.between(oldest, now))
                .orElse(Duration.ZERO));
        List<OutboxEventRecord> events;
        List<Future<Boolean>> futures = new ArrayList<>();
        synchronized (lifecycleMonitor) {
            if (closing.get()) {
                return 0;
            }
            events = store.claimDue(now, properties.getBatchSize(), properties.getClaimLease());
            for (OutboxEventRecord event : events) {
                try {
                    futures.add(workers.submit(() -> publishOne(event)));
                } catch (RejectedExecutionException rejected) {
                    LOGGER.warn("Outbox relay rejected a claimed event during shutdown eventId={}", event.eventId());
                }
            }
        }
        if (events.isEmpty()) {
            return 0;
        }

        int completed = 0;
        for (Future<Boolean> future : futures) {
            try {
                if (future.get()) {
                    completed++;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return completed;
            } catch (Exception workerFailure) {
                LOGGER.error("Outbox worker stopped unexpectedly", workerFailure);
            }
        }
        return completed;
    }

    private boolean publishOne(OutboxEventRecord event) {
        Instant started = clock.instant();
        Instant now = clock.instant();
        try {
            publisher.publish(event, EventSubjects.forAggregate(event.aggregateType(), event.aggregateId()));
            store.markPublished(event, clock.instant());
            metrics.outboxPublished();
            metrics.outboxPublish(Duration.between(started, clock.instant()));
            return true;
        } catch (Exception publishFailure) {
            metrics.outboxPublishFailed();
            return handleFailure(event, publishFailure, now);
        }
    }

    private boolean handleFailure(OutboxEventRecord event, Exception publishFailure, Instant failedAt) {
        String safeError = OutboxErrorSanitizer.safeFailure(publishFailure);
        if (event.attempts() < properties.getMaxAttempts()) {
            Instant nextAttempt = failedAt.plus(properties.retryDelayForAttempt(event.attempts()));
            store.markRetry(event, nextAttempt, safeError, failedAt);
            metrics.outboxRetried();
            LOGGER.warn("Outbox event publish failed; retry scheduled eventId={} attempt={} nextAttemptAt={}",
                    event.eventId(), event.attempts(), nextAttempt);
            return true;
        }

        try {
            publisher.publishDeadLetter(event, EventSubjects.DEADLETTER_EVENTS);
            store.markDeadLetter(event, failedAt);
            metrics.outboxDeadLettered();
            LOGGER.error("Outbox event moved to dead-letter eventId={} aggregateType={} aggregateId={} "
                            + "eventType={} attempt={}", event.eventId(), event.aggregateType(), event.aggregateId(),
                    event.eventType(), event.attempts());
            return true;
        } catch (Exception deadLetterFailure) {
            Instant nextAttempt = failedAt.plus(properties.retryDelayForAttempt(event.attempts()));
            store.markRetry(event, nextAttempt, OutboxErrorSanitizer.safeFailure(deadLetterFailure), failedAt);
            metrics.outboxRetried();
            LOGGER.error("Outbox dead-letter publish failed; event remains retryable eventId={} attempt={}",
                    event.eventId(), event.attempts());
            return true;
        }
    }

    @Override
    public void close() {
        close(properties.getShutdownTimeout());
    }

    public void close(java.time.Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        synchronized (lifecycleMonitor) {
            if (closing.compareAndSet(false, true)) {
                workers.shutdown();
            }
        }
        try {
            if (!workers.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                LOGGER.warn("Outbox relay shutdown timed out with in-flight workers still running");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Outbox relay shutdown interrupted; in-flight workers continue", interrupted);
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread worker = new Thread(runnable, "outbox-relay-" + sequence.incrementAndGet());
            worker.setDaemon(true);
            return worker;
        };
    }
}
