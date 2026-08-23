package io.agentteams.controlplane.agentspec;

import java.util.Objects;

/** Raised by AgentSpec lifecycle operations when a reference result is not valid. */
public final class AgentSpecReferenceValidationException extends IllegalArgumentException {

    private final AgentSpecReferenceValidationResult result;

    public AgentSpecReferenceValidationException(AgentSpecReferenceValidationResult result) {
        super(message(result));
        this.result = Objects.requireNonNull(result, "result");
        if (result.isValid()) {
            throw new IllegalArgumentException("exception requires an invalid result");
        }
    }

    public AgentSpecReferenceValidationResult result() {
        return result;
    }

    public AgentSpecReferenceValidationResult.Category category() {
        return result.category();
    }

    private static String message(AgentSpecReferenceValidationResult result) {
        Objects.requireNonNull(result, "result");
        return "agent spec reference validation failed: " + result.code();
    }
}
