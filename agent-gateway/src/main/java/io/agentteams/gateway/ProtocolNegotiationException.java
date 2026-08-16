package io.agentteams.gateway;

/** Raised by a negotiation port when the peer cannot be spoken to safely. */
public final class ProtocolNegotiationException extends RuntimeException {

    public ProtocolNegotiationException(String message) {
        super(message);
    }
}
