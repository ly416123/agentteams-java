package io.agentteams.gateway;

import java.util.Optional;

@FunctionalInterface
public interface AgentSessionStore {
    Optional<AgentSession> findByTokenSha256(String tokenSha256);
}
