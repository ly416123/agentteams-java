package io.agentteams.manager.conversation;

import java.util.Objects;

/** Safe, stable failure raised at the Conversation runtime boundary. */
public final class ConversationRuntimeException extends RuntimeException {
    public enum Code {
        WORKER_UNAVAILABLE,
        MODEL_PROVIDER_UNAVAILABLE,
        HTTP_FAILURE,
        TIMEOUT,
        PROTOCOL_ERROR,
        CONNECTION_CLOSED,
        CANCELLED,
        IDEMPOTENCY_CONFLICT,
        SESSION_NOT_FOUND,
        INVALID_STATE,
        RESOURCE_EXHAUSTED
    }

    private final Code code;

    public ConversationRuntimeException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ConversationRuntimeException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }

    public static ConversationRuntimeException workerUnavailable() {
        return new ConversationRuntimeException(Code.WORKER_UNAVAILABLE, "worker is unavailable");
    }

    public static ConversationRuntimeException sessionNotFound() {
        return new ConversationRuntimeException(Code.SESSION_NOT_FOUND, "conversation session was not found");
    }
}
