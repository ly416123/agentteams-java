package io.agentteams.manager.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
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
}
