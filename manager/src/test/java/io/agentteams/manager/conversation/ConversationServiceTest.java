package io.agentteams.manager.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConversationServiceTest {
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final ConversationRuntimePort.Context CONTEXT = new ConversationRuntimePort.Context(
            "project-a", "team-a", "worker-a", null, SESSION_ID);

    @Test
    void runtimeExceptionRequiresAStableCode() {
        assertThatThrownBy(() -> new ConversationRuntimeException(null, "invalid"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void createAndStartAreIdempotentAndExposeStartedEvent() {
        FakeConversationRuntime runtime = new FakeConversationRuntime();
        ConversationService service = new ConversationService(runtime);

        assertThat(service.create(CONTEXT).sessionId()).isEqualTo(SESSION_ID);
        assertThat(service.create(CONTEXT)).isEqualTo(service.create(CONTEXT));
        assertThat(service.start(SESSION_ID).status()).isEqualTo(ConversationService.Status.ACTIVE);
        assertThat(service.start(SESSION_ID).status()).isEqualTo(ConversationService.Status.ACTIVE);
        assertThat(service.events(SESSION_ID, 0)).extracting(ConversationEvent::type)
                .containsExactly("conversation.started");
    }

    @Test
    void getReturnsTheConversationSnapshotAndRejectsUnknownSessions() {
        ConversationService service = new ConversationService(new FakeConversationRuntime());
        service.create(CONTEXT);

        assertThat(service.get(SESSION_ID).context()).isEqualTo(CONTEXT);
        assertThatThrownBy(() -> service.get(UUID.randomUUID()))
                .isInstanceOf(ConversationRuntimeException.class)
                .satisfies(error -> assertThat(((ConversationRuntimeException) error).code())
                        .isEqualTo(ConversationRuntimeException.Code.SESSION_NOT_FOUND));
    }

    @Test
    void sameMessageIdempotencyKeyReturnsTheSameEventsWithoutDuplicatingRuntimeWork() {
        FakeConversationRuntime runtime = new FakeConversationRuntime();
        ConversationService service = new ConversationService(runtime);
        service.create(CONTEXT);
        service.start(SESSION_ID);

        ConversationService.SendResult first = service.send(SESSION_ID, "message-1", "hello");
        ConversationService.SendResult replay = service.send(SESSION_ID, "message-1", "hello");

        assertThat(replay).isEqualTo(first);
        assertThat(service.events(SESSION_ID, 0)).hasSize(3);
    }

    @Test
    void incrementsVersionForLifecycleAndMessageWritesAndRejectsStaleWrites() {
        ConversationService service = startedService();

        assertThat(service.get(SESSION_ID).version()).isEqualTo(1);
        service.send(SESSION_ID, "message-1", "hello", 1L);
        assertThat(service.get(SESSION_ID).version()).isEqualTo(2);
        assertThatThrownBy(() -> service.send(SESSION_ID, "message-2", "stale", 1L))
                .isInstanceOf(ConversationVersionConflictException.class)
                .satisfies(error -> {
                    ConversationVersionConflictException conflict = (ConversationVersionConflictException) error;
                    assertThat(conflict.expectedVersion()).isEqualTo(1);
                    assertThat(conflict.actualVersion()).isEqualTo(2);
                });
    }

    @Test
    void idempotentReplayReturnsTheOriginalAsyncSendResponse() throws Exception {
        DelayedRuntime runtime = new DelayedRuntime();
        ConversationService service = new ConversationService(runtime);
        service.create(CONTEXT);
        service.start(SESSION_ID);

        ConversationService.SendResult initial = service.send(SESSION_ID, "message-1", "hello");
        assertThat(initial.events()).isEmpty();
        assertThat(runtime.sendStarted.await(5, TimeUnit.SECONDS)).isTrue();
        runtime.release.countDown();
        assertThat(runtime.completed.await(5, TimeUnit.SECONDS)).isTrue();

        ConversationService.SendResult replay = service.send(SESSION_ID, "message-1", "hello");
        assertThat(initial.events()).isEmpty();
        assertThat(replay.events()).extracting(ConversationEvent::type)
                .containsExactly("message.delta", "message.completed");
    }

    @Test
    void sharedRepositoryReservesAnIdempotencyKeyBeforeOnlyOneRuntimeDispatch() throws Exception {
        InMemoryConversationRepository repository = new InMemoryConversationRepository();
        DelayedRuntime runtime = new DelayedRuntime();
        ConversationService first = new ConversationService(runtime, repository);
        ConversationService second = new ConversationService(runtime, repository);
        first.createAndStart(CONTEXT);
        second.create(CONTEXT);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var dispatched = executor.submit(() -> first.send(SESSION_ID, "message-1", "hello"));
            assertThat(runtime.sendStarted.await(5, TimeUnit.SECONDS)).isTrue();

            ConversationService.SendResult duplicate = second.send(SESSION_ID, "message-1", "hello");
            assertThat(duplicate.events()).isEmpty();
            assertThat(runtime.sendCount.get()).isEqualTo(1);

            runtime.release.countDown();
            dispatched.get(5, TimeUnit.SECONDS);
        } finally {
            runtime.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void eventReaderOnAnotherManagerReplicaWaitsForTheSharedReservation() throws Exception {
        InMemoryConversationRepository repository = new InMemoryConversationRepository();
        DelayedRuntime runtime = new DelayedRuntime();
        ConversationService first = new ConversationService(runtime, repository);
        ConversationService second = new ConversationService(runtime, repository);
        first.createAndStart(CONTEXT);
        second.create(CONTEXT);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var dispatched = executor.submit(() -> first.send(SESSION_ID, "message-1", "hello"));
            assertThat(runtime.sendStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(second.hasPendingMessage(SESSION_ID)).isTrue();

            runtime.release.countDown();
            assertThat(runtime.completed.await(5, TimeUnit.SECONDS)).isTrue();
            dispatched.get(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (second.hasPendingMessage(SESSION_ID) && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertThat(second.hasPendingMessage(SESSION_ID)).isFalse();
            assertThat(second.events(SESSION_ID, 0)).extracting(ConversationEvent::type)
                    .contains("message.completed");
        } finally {
            runtime.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void managerRestartTurnsAnUncertainInFlightMessageIntoRecoveryRequired() throws Exception {
        InMemoryConversationRepository repository = new InMemoryConversationRepository();
        DelayedRuntime runtime = new DelayedRuntime();
        ConversationService first = new ConversationService(runtime, repository);
        first.createAndStart(CONTEXT);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var dispatched = executor.submit(() -> first.send(SESSION_ID, "message-1", "hello"));
            assertThat(runtime.sendStarted.await(5, TimeUnit.SECONDS)).isTrue();

            ConversationService restarted = new ConversationService(new FakeConversationRuntime(), repository);
            assertThatThrownBy(() -> restarted.send(SESSION_ID, "message-1", "hello"))
                    .isInstanceOf(ConversationRuntimeException.class)
                    .satisfies(error -> assertThat(((ConversationRuntimeException) error).code())
                            .isEqualTo(ConversationRuntimeException.Code.RECOVERY_REQUIRED));
            assertThat(repository.findMessage(SESSION_ID, "message-1").orElseThrow().status())
                    .isEqualTo(ConversationRepository.MessageStatus.RECOVERY_REQUIRED);
            assertThat(restarted.events(SESSION_ID, 0)).extracting(ConversationEvent::type)
                    .contains("conversation.recovery_required");

            runtime.release.countDown();
            dispatched.get(5, TimeUnit.SECONDS);
        } finally {
            runtime.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void sourceEventIdentityDeduplicatesRepeatedSseFrames() {
        InMemoryConversationRepository repository = new InMemoryConversationRepository();
        repository.saveSession(CONTEXT, ConversationService.Status.ACTIVE, java.time.Instant.EPOCH);
        ConversationEvent first = ConversationEvent.of(SESSION_ID, 1, "message.delta", "{\"text\":\"hi\"}",
                java.time.Instant.EPOCH, "sse-1");

        ConversationEvent replay = repository.appendEvent(SESSION_ID, first);
        ConversationEvent duplicate = repository.appendEvent(SESSION_ID, first);

        assertThat(duplicate).isEqualTo(replay);
        assertThat(repository.findEvents(SESSION_ID)).containsExactly(replay);
        assertThatThrownBy(() -> repository.appendEvent(SESSION_ID,
                ConversationEvent.of(SESSION_ID, 2, "message.delta", "{\"text\":\"changed\"}",
                        java.time.Instant.EPOCH, "sse-1")))
                .isInstanceOf(ConversationRuntimeException.class)
                .satisfies(error -> assertThat(((ConversationRuntimeException) error).code())
                        .isEqualTo(ConversationRuntimeException.Code.PROTOCOL_ERROR));
    }

    @Test
    void idempotentReplayDoesNotIncludeEventsFromTheNextMessage() {
        FakeConversationRuntime runtime = new FakeConversationRuntime();
        ConversationService service = new ConversationService(runtime);
        service.create(CONTEXT);
        service.start(SESSION_ID);

        service.send(SESSION_ID, "message-1", "first");
        service.send(SESSION_ID, "message-2", "second");

        ConversationService.SendResult replay = service.send(SESSION_ID, "message-1", "first");

        assertThat(replay.events()).hasSize(2)
                .allSatisfy(event -> assertThat(event.data()).contains("first").doesNotContain("second"));
    }

    @Test
    void queuesSupplementalMessageUntilTheCurrentExecutionCompletes() throws Exception {
        SerialRuntime runtime = new SerialRuntime();
        ConversationService service = new ConversationService(runtime);
        service.createAndStart(CONTEXT);

        service.send(SESSION_ID, "message-1", "first");
        ConversationService.SendResult queued = service.send(SESSION_ID, "message-2", "supplement");

        assertThat(queued.events()).isEmpty();
        assertThat(runtime.sendCount.get()).isEqualTo(1);
        runtime.releaseFirst.countDown();
        assertThat(runtime.secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
        runtime.releaseSecond.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (runtime.sendCount.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(runtime.sendCount.get()).isEqualTo(2);
        while (service.events(SESSION_ID, 0).size() < 5 && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(service.events(SESSION_ID, 0)).extracting(ConversationEvent::type)
                .containsExactly("conversation.started", "message.delta", "message.completed",
                        "message.delta", "message.completed");
    }

    @Test
    void reusingMessageIdempotencyKeyForDifferentContentIsRejected() {
        ConversationService service = startedService();
        service.send(SESSION_ID, "message-1", "hello");

        assertThatThrownBy(() -> service.send(SESSION_ID, "message-1", "different"))
                .isInstanceOf(ConversationRuntimeException.class)
                .satisfies(error -> assertThat(((ConversationRuntimeException) error).code())
                        .isEqualTo(ConversationRuntimeException.Code.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void cancellationIsIdempotentAndPreventsFurtherMessages() {
        ConversationService service = startedService();

        service.cancel(SESSION_ID, "cancel-1");
        service.cancel(SESSION_ID, "cancel-1");

        assertThat(service.events(SESSION_ID, 0)).extracting(ConversationEvent::type)
                .containsExactly("conversation.started", "conversation.cancelled");
        assertThatThrownBy(() -> service.send(SESSION_ID, "message-1", "late"))
                .isInstanceOf(ConversationRuntimeException.class)
                .satisfies(error -> assertThat(((ConversationRuntimeException) error).code())
                        .isEqualTo(ConversationRuntimeException.Code.CANCELLED));
    }

    @Test
    void restoresConversationHistoryAndMessageIdempotencyAfterManagerRestart() {
        InMemoryConversationRepository repository = new InMemoryConversationRepository();
        ConversationService first = new ConversationService(new FakeConversationRuntime(), repository);
        first.createAndStart(CONTEXT);
        first.send(SESSION_ID, "message-1", "hello");

        ConversationService restarted = new ConversationService(new FakeConversationRuntime(), repository);

        assertThat(restarted.get(SESSION_ID).status()).isEqualTo(ConversationService.Status.ACTIVE);
        assertThat(restarted.history(SESSION_ID).messages()).extracting(
                ConversationRepository.MessageRecord::content).containsExactly("hello");
        assertThat(restarted.events(SESSION_ID, 0)).extracting(ConversationEvent::type)
                .containsExactly("conversation.started", "message.delta", "message.completed");
        assertThat(restarted.send(SESSION_ID, "message-1", "hello").events())
                .extracting(ConversationEvent::type)
                .containsExactly("message.delta", "message.completed");
    }

    @Test
    void workerUnavailableIsNotPresentedAsACompletedConversation() {
        ConversationService service = new ConversationService(new FakeConversationRuntime(false));
        service.create(CONTEXT);

        assertThatThrownBy(() -> service.start(SESSION_ID))
                .isInstanceOf(ConversationRuntimeException.class)
                .satisfies(error -> assertThat(((ConversationRuntimeException) error).code())
                        .isEqualTo(ConversationRuntimeException.Code.WORKER_UNAVAILABLE));
    }

    private static ConversationService startedService() {
        ConversationService service = new ConversationService(new FakeConversationRuntime());
        service.create(CONTEXT);
        service.start(SESSION_ID);
        return service;
    }

    private static final class DelayedRuntime implements ConversationRuntimePort {
        private final List<ConversationEvent> events = new ArrayList<>();
        private final CountDownLatch sendStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicInteger sendCount = new AtomicInteger();

        @Override
        public void start(Context context) {
            synchronized (events) {
                if (events.isEmpty()) {
                    events.add(ConversationEvent.of(SESSION_ID, 1, "conversation.started", "{}",
                            java.time.Instant.EPOCH));
                }
            }
        }

        @Override
        public void send(Message message) {
            sendCount.incrementAndGet();
            sendStarted.countDown();
            Thread thread = new Thread(() -> {
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                synchronized (events) {
                    events.add(ConversationEvent.of(SESSION_ID, 2, "message.delta",
                            "{\"text\":\"hel\"}", java.time.Instant.EPOCH));
                    events.add(ConversationEvent.of(SESSION_ID, 3, "message.completed",
                            "{\"text\":\"hello\"}", java.time.Instant.EPOCH));
                }
                completed.countDown();
            });
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public List<ConversationEvent> events(UUID sessionId, long afterCursor) {
            synchronized (events) {
                return events.stream().filter(event -> event.cursor() > afterCursor).toList();
            }
        }

        @Override
        public void cancel(UUID sessionId) { }
    }

    private static final class SerialRuntime implements ConversationRuntimePort {
        private final List<ConversationEvent> events = new ArrayList<>();
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch releaseSecond = new CountDownLatch(1);
        private final CountDownLatch secondStarted = new CountDownLatch(1);
        private final AtomicInteger sendCount = new AtomicInteger();

        @Override
        public void start(Context context) {
            synchronized (events) {
                events.add(ConversationEvent.of(SESSION_ID, 1, "conversation.started", "{}",
                        java.time.Instant.EPOCH));
            }
        }

        @Override
        public void send(Message message) {
            synchronized (events) {
                if (sendCount.get() > 0 && events.size() < 3) {
                    throw new ConversationRuntimeException(ConversationRuntimeException.Code.INVALID_STATE,
                            "conversation already has a request in flight");
                }
            }
            int number = sendCount.incrementAndGet();
            if (number > 2) throw new AssertionError("unexpected third dispatch");
            Thread thread = new Thread(() -> {
                try {
                    if (number == 1) releaseFirst.await();
                    else {
                        secondStarted.countDown();
                        releaseSecond.await();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                synchronized (events) {
                    events.add(ConversationEvent.of(SESSION_ID, events.size() + 1, "message.delta",
                            "{\"text\":\"" + message.content() + "\"}", java.time.Instant.EPOCH));
                    events.add(ConversationEvent.of(SESSION_ID, events.size() + 1, "message.completed",
                            "{\"text\":\"" + message.content() + "\"}", java.time.Instant.EPOCH));
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public List<ConversationEvent> events(UUID sessionId, long afterCursor) {
            synchronized (events) {
                return events.stream().filter(event -> event.cursor() > afterCursor).toList();
            }
        }

        @Override
        public void cancel(UUID sessionId) { }
    }
}
