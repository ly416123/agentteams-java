package io.agentteams.gateway;

import io.agentteams.contracts.v1.AgentHello;

/** Compatibility and convenience facade for the authentication port. */
@FunctionalInterface
public interface AgentAuthenticator extends AuthenticationPort {

    static AgentAuthenticator allowAll() {
        return (connection, hello) -> AuthenticationPort.AuthenticationDecision.allow();
    }
}
