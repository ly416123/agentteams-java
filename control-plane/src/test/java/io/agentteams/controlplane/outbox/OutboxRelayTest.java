package io.agentteams.controlplane.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.controlplane.observability.ControlPlaneMetrics;
import io.agentteams.controlplane.observability.TaskMetricsPort;
import io.agentteams.controlplane.persistence.OutboxEventRecord;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    @Test
    void marksPublishedOnlyAfterPublisherAcknowledges() {
        FakeStore store = new FakeStore(event(1));
        RecordingPublisher publisher = new RecordingPublisher();
        try (OutboxRelay relay = relay(store, publisher)) {
            assertThat(relay.relayOnce()).isEqualTo(1);
        }

        assertThat(publisher.subjects).containsExactly("task.events." + store.event.aggregateId());
        assertThat(store.published).containsExactly(store.event.eventId());
        assertThat(store.retries).isEmpty();
    }

    @Test
    void recordsBacklogAndPublishOutcomeMetrics() {
        FakeStore store = new FakeStore(event(1));
        store.pending = 4;
        store.oldestPendingAt = NOW.minusSeconds(42);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ControlPlaneMetrics metrics = new ControlPlaneMetrics(registry);
        try (OutboxRelay relay = relay(store, new RecordingPublisher(), metrics)) {
            assertThat(relay.relayOnce()).isEqualTo(1);
        }

        assertThat(registry.find("agentteams.outbox.backlog").gauge().value()).isEqualTo(4);
        assertThat(registry.find("agentteams.outbox.oldest.pending.age.seconds").gauge().value()).isEqualTo(42);
        assertThat(registry.counter("agentteams.outbox.published").count()).isEqualTo(1);
        assertThat(registry.timer("agentteams.outbox.publish.latency").count()).isEqualTo(1);
    }

    @Test
    void retriesWithCappedExponentialDelayAndDeadLettersOnTheTenthAttempt() {
        FakeStore store = new FakeStore(event(10));
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.failNormalPublishes = true;
        try (OutboxRelay relay = relay(store, publisher)) {
            relay.relayOnce();
        }

        assertThat(publisher.subjects).containsExactly(EventSubjects.DEADLETTER_EVENTS);
        assertThat(store.deadLetters).containsExactly(store.event.eventId());
        assertThat(store.retries).isEmpty();
        assertThat(store.errors).allMatch(error -> !error.contains("safe task body"));
    }

    @Test
    void keepsAFailedAttemptPendingWithBoundedRetryDelay() {
        FakeStore store = new FakeStore(event(1));
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.failNormalPublishes = true;
        try (OutboxRelay relay = relay(store, publisher)) {
            relay.relayOnce();
        }

        assertThat(store.retries).containsExactly(store.event.eventId());
        assertThat(Duration.between(NOW, store.nextRetryAt)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void deadLetterFailureRemainsRecoverableInDatabaseState() {
        FakeStore store = new FakeStore(event(10));
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.failNormalPublishes = true;
        publisher.failDeadLetterPublishes = true;
        try (OutboxRelay relay = relay(store, publisher)) {
            relay.relayOnce();
        }

        assertThat(store.deadLetters).isEmpty();
        assertThat(store.retries).containsExactly(store.event.eventId());
    }

    @Test
    void closeStopsNewClaimsAndWaitsForInFlightWorkerWithoutInterruptingIt() throws Exception {
        FakeStore store = new FakeStore(event(1));
        BlockingPublisher publisher = new BlockingPublisher();
        OutboxRelay relay = relay(store, publisher);
        AtomicInteger result = new AtomicInteger(-1);
        Thread relayThread = new Thread(() -> result.set(relay.relayOnce()), "relay-test");
        relayThread.start();
        assertThat(publisher.started.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        relay.close(Duration.ofMillis(10));

        assertThat(publisher.interrupted.get()).isFalse();
        assertThat(relay.relayOnce()).isZero();
        assertThat(result).hasValue(-1);

        publisher.release.countDown();
        relayThread.join(1_000);
        relay.close(Duration.ofSeconds(1));

        assertThat(result).hasValue(1);
        assertThat(store.published).containsExactly(store.event.eventId());
    }

    @Test
    void relayOnceRestoresInterruptAndReportsOnlyCompletedWork() throws Exception {
        FakeStore store = new FakeStore(event(1));
        BlockingPublisher publisher = new BlockingPublisher();
        OutboxRelay relay = relay(store, publisher);
        AtomicInteger result = new AtomicInteger(-1);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread relayThread = new Thread(() -> {
            result.set(relay.relayOnce());
            interruptRestored.set(Thread.currentThread().isInterrupted());
        }, "relay-interrupt-test");
        relayThread.start();
        assertThat(publisher.started.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        relayThread.interrupt();
        relayThread.join(1_000);

        assertThat(result).hasValue(0);
        assertThat(interruptRestored).isTrue();

        publisher.release.countDown();
        relay.close(Duration.ofSeconds(1));
        assertThat(store.published).containsExactly(store.event.eventId());
    }

    @Test
    void closeRestoresInterruptWhenShutdownWaitIsInterrupted() throws Exception {
        FakeStore store = new FakeStore(event(1));
        BlockingPublisher publisher = new BlockingPublisher();
        OutboxRelay relay = relay(store, publisher);
        Thread relayThread = new Thread(relay::relayOnce, "relay-shutdown-interrupt-test");
        relayThread.start();
        assertThat(publisher.started.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread closer = new Thread(() -> {
            Thread.currentThread().interrupt();
            relay.close(Duration.ofSeconds(1));
            interruptRestored.set(Thread.currentThread().isInterrupted());
        }, "relay-close-interrupt-test");
        closer.start();
        closer.join(1_000);

        assertThat(interruptRestored).isTrue();
        publisher.release.countDown();
        relayThread.join(1_000);
        relay.close(Duration.ofSeconds(1));
    }

    private static OutboxRelay relay(FakeStore store, EventPublisher publisher) {
        return relay(store, publisher, TaskMetricsPort.noop());
    }

    private static OutboxRelay relay(FakeStore store, EventPublisher publisher,
            io.agentteams.controlplane.observability.TaskMetricsPort metrics) {
        OutboxRelayProperties properties = new OutboxRelayProperties();
        properties.setConcurrency(2);
        properties.setBatchSize(2);
        properties.setMaxAttempts(10);
        properties.setBaseRetryDelay(Duration.ofSeconds(1));
        properties.setMaxRetryDelay(Duration.ofSeconds(5));
        return new OutboxRelay(store, publisher, properties,
                Clock.fixed(NOW, ZoneOffset.UTC), metrics);
    }

    private static OutboxEventRecord event(int attempts) {
        return OutboxEventRecord.pending(UUID.randomUUID(), "task", UUID.randomUUID(), "TaskCreated",
                "{\"description\":\"safe task body\",\"token\":\"secret\"}", 3, NOW, NOW)
                .withAttempts(attempts);
    }

    private static final class FakeStore implements OutboxStore {
        private final OutboxEventRecord event;
        private final List<UUID> published = new ArrayList<>();
        private final List<UUID> retries = new ArrayList<>();
        private final List<UUID> deadLetters = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private Instant nextRetryAt;
        private long pending = -1;
        private Instant oldestPendingAt;

        private FakeStore(OutboxEventRecord event) {
            this.event = event;
        }

        @Override
        public long pendingCount() {
            return pending;
        }

        @Override
        public java.util.Optional<Instant> oldestPendingAt() {
            return java.util.Optional.ofNullable(oldestPendingAt);
        }

        @Override
        public List<OutboxEventRecord> claimDue(Instant now, int limit, Duration lease) {
            return List.of(event);
        }

        @Override
        public void markPublished(OutboxEventRecord event, Instant at) {
            published.add(event.eventId());
        }

        @Override
        public void markRetry(OutboxEventRecord event, Instant nextAttemptAt, String error, Instant at) {
            retries.add(event.eventId());
            this.nextRetryAt = nextAttemptAt;
            errors.add(error);
        }

        @Override
        public void markDeadLetter(OutboxEventRecord event, Instant at) {
            deadLetters.add(event.eventId());
        }
    }

    private static final class RecordingPublisher implements EventPublisher {
        private final List<String> subjects = new ArrayList<>();
        private boolean failNormalPublishes;
        private boolean failDeadLetterPublishes;

        @Override
        public void publish(OutboxEventRecord event, String subject) {
            if (EventSubjects.DEADLETTER_EVENTS.equals(subject) && failDeadLetterPublishes
                    || !EventSubjects.DEADLETTER_EVENTS.equals(subject) && failNormalPublishes) {
                throw new IllegalStateException("task body and token=secret");
            }
            subjects.add(subject);
        }

        @Override
        public void publishDeadLetter(OutboxEventRecord event, String subject) {
            if (failDeadLetterPublishes) {
                throw new IllegalStateException("task body and token=secret");
            }
            subjects.add(subject);
        }
    }

    private static final class BlockingPublisher implements EventPublisher {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean interrupted = new AtomicBoolean();

        @Override
        public void publish(OutboxEventRecord event, String subject) {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException interruptedException) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        }
    }
}
