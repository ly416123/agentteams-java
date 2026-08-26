package io.agentteams.manager.security;

import java.util.Optional;

@FunctionalInterface
public interface ManagerIdentityTokenValidator {
    Optional<ManagerPrincipal> validate(String token);
}
