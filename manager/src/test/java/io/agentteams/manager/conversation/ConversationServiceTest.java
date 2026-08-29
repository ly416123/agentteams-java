package io.agentteams.manager.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        assertThat(replay).isEqualTo(initial);
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

        @Override
        public void start(Context context) {
            synchronized (events) {
                events.add(ConversationEvent.of(SESSION_ID, 1, "conversation.started", "{}",
                        java.time.Instant.EPOCH));
            }
        }

        @Override
        public void send(Message message) {
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
}
