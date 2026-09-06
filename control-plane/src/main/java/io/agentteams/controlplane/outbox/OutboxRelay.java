package io.agentteams.controlplane.outbox;

import io.agentteams.controlplane.persistence.OutboxEventRecord;
import io.agentteams.observability.TaskMetricsPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
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
    private final Semaphore capacity;
    private final Map<UUID, CompletableFuture<DispatchResult>> tails = new java.util.concurrent.ConcurrentHashMap<>();
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
        int queueCapacity = Math.max(properties.getConcurrency(), Math.min(properties.getBatchSize(),
                properties.getConcurrency() * 4));
        this.capacity = new Semaphore(queueCapacity);
        this.workers = new ThreadPoolExecutor(properties.getConcurrency(), properties.getConcurrency(), 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity), daemonThreadFactory());
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
        List<Future<DispatchResult>> futures = new ArrayList<>();
        synchronized (lifecycleMonitor) {
            if (closing.get()) {
                return 0;
            }
            events = store.claimDue(now, properties.getBatchSize(), properties.getClaimLease());
            events = new ArrayList<>(events);
            events.sort(Comparator.comparing(OutboxEventRecord::aggregateId)
                    .thenComparingLong(OutboxEventRecord::aggregateVersion)
                    .thenComparing(OutboxEventRecord::occurredAt)
                    .thenComparing(OutboxEventRecord::createdAt)
                    .thenComparing(OutboxEventRecord::id));
            for (OutboxEventRecord event : events) {
                try {
                    futures.add(submit(event));
                } catch (RejectedExecutionException rejected) {
                    deferAfterDispatchRejection(event, now, rejected);
                    futures.add(CompletableFuture.completedFuture(DispatchResult.DEFERRED));
                }
            }
        }
        if (events.isEmpty()) {
            return 0;
        }

        int completed = 0;
        for (int index = 0; index < futures.size(); index++) {
            Future<DispatchResult> future = futures.get(index);
            try {
                DispatchResult result = future.get();
                if (result == DispatchResult.SKIPPED) {
                    deferAfterOrdering(events.get(index), now);
                }
                completed++;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return completed;
            } catch (Exception workerFailure) {
                LOGGER.error("Outbox worker stopped unexpectedly", workerFailure);
                deferAfterDispatchRejection(events.get(index), now, workerFailure);
                completed++;
            }
        }
        return completed;
    }

    private Future<DispatchResult> submit(OutboxEventRecord event) {
        if (closing.get() || !capacity.tryAcquire()) {
            throw new RejectedExecutionException("Outbox relay dispatcher is full or closed");
        }
        UUID aggregateId = event.aggregateId();
        CompletableFuture<DispatchResult> next;
        synchronized (tails) {
            try {
                CompletableFuture<DispatchResult> previous = tails.get(aggregateId);
                if (previous == null) {
                    next = CompletableFuture.supplyAsync(() -> publishOne(event), workers);
                } else {
                    next = previous.thenComposeAsync(result -> result == DispatchResult.PUBLISHED
                            ? CompletableFuture.supplyAsync(() -> publishOne(event), workers)
                            : CompletableFuture.completedFuture(DispatchResult.SKIPPED), workers);
                }
                tails.put(aggregateId, next);
            } catch (RejectedExecutionException rejected) {
                capacity.release();
                throw rejected;
            }
        }
        next.whenComplete((ignored, error) -> {
            synchronized (tails) {
                if (tails.get(aggregateId) == next) {
                    tails.remove(aggregateId);
                }
            }
            capacity.release();
        });
        return next;
    }

    private DispatchResult publishOne(OutboxEventRecord event) {
        Instant started = clock.instant();
        Instant now = clock.instant();
        try {
            publisher.publish(event, EventSubjects.forAggregate(event.aggregateType(), event.aggregateId()));
            store.markPublished(event, clock.instant());
            metrics.outboxPublished();
            metrics.outboxPublish(Duration.between(started, clock.instant()));
            return DispatchResult.PUBLISHED;
        } catch (Exception publishFailure) {
            metrics.outboxPublishFailed();
            return handleFailure(event, publishFailure, now);
        }
    }

    private DispatchResult handleFailure(OutboxEventRecord event, Exception publishFailure, Instant failedAt) {
        String safeError = OutboxErrorSanitizer.safeFailure(publishFailure);
        if (event.attempts() < properties.getMaxAttempts()) {
            Instant nextAttempt = failedAt.plus(properties.retryDelayForAttempt(event.attempts()));
            store.markRetry(event, nextAttempt, safeError, failedAt);
            metrics.outboxRetried();
            LOGGER.warn("Outbox event publish failed; retry scheduled eventId={} attempt={} nextAttemptAt={}",
                    event.eventId(), event.attempts(), nextAttempt);
            return DispatchResult.DEFERRED;
        }

        try {
            publisher.publishDeadLetter(event, EventSubjects.DEADLETTER_EVENTS);
            store.markDeadLetter(event, failedAt);
            metrics.outboxDeadLettered();
            LOGGER.error("Outbox event moved to dead-letter eventId={} aggregateType={} aggregateId={} "
                            + "eventType={} attempt={}", event.eventId(), event.aggregateType(), event.aggregateId(),
                    event.eventType(), event.attempts());
            return DispatchResult.PUBLISHED;
        } catch (Exception deadLetterFailure) {
            Instant nextAttempt = failedAt.plus(properties.retryDelayForAttempt(event.attempts()));
            store.markRetry(event, nextAttempt, OutboxErrorSanitizer.safeFailure(deadLetterFailure), failedAt);
            metrics.outboxRetried();
            LOGGER.error("Outbox dead-letter publish failed; event remains retryable eventId={} attempt={}",
                    event.eventId(), event.attempts());
            return DispatchResult.DEFERRED;
        }
    }

    private void deferAfterOrdering(OutboxEventRecord event, Instant now) {
        Instant nextAttempt = now.plus(properties.retryDelayForAttempt(event.attempts()));
        store.markRetry(event, nextAttempt, "aggregate predecessor is retrying", now);
        metrics.outboxRetried();
        LOGGER.warn("Outbox event deferred until aggregate predecessor succeeds eventId={} nextAttemptAt={}",
                event.eventId(), nextAttempt);
    }

    private void deferAfterDispatchRejection(OutboxEventRecord event, Instant now, Throwable failure) {
        Instant nextAttempt = now.plus(properties.retryDelayForAttempt(event.attempts()));
        store.markRetry(event, nextAttempt, OutboxErrorSanitizer.safeFailure(failure), now);
        metrics.outboxRetried();
        LOGGER.warn("Outbox event deferred because relay dispatcher rejected it eventId={} nextAttemptAt={}",
                event.eventId(), nextAttempt);
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

    private enum DispatchResult {
        PUBLISHED,
        DEFERRED,
        SKIPPED
    }
}
