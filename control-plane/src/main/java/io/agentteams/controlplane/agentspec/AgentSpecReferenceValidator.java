package io.agentteams.controlplane.agentspec;

/** Injectable boundary for validating AgentSpec resource references. */
@FunctionalInterface
public interface AgentSpecReferenceValidator {

    AgentSpecReferenceValidationResult validate(AgentSpecReferenceValidationRequest request);
}
