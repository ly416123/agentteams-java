package io.agentteams.controlplane.agentspec;

/** Default validator used when no resource registry adapter is installed. */
public final class NoopAgentSpecReferenceValidator implements AgentSpecReferenceValidator {

    @Override
    public AgentSpecReferenceValidationResult validate(AgentSpecReferenceValidationRequest request) {
        if (request == null) {
            throw new NullPointerException("request");
        }
        return AgentSpecReferenceValidationResult.valid();
    }
}
