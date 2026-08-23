package io.agentteams.controlplane.security;

public final class OutboundPolicyViolationException extends IllegalArgumentException {

    public OutboundPolicyViolationException(String message) {
        super(message);
    }
}
