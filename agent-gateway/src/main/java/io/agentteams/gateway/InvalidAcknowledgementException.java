package io.agentteams.gateway;

/** Typed protocol error for an acknowledgement that cannot safely advance durable replay state. */
public final class InvalidAcknowledgementException extends RuntimeException {

    public InvalidAcknowledgementException(String message) {
        super(message);
    }
}
