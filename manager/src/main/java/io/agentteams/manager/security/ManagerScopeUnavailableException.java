package io.agentteams.manager.security;

/** Indicates that the Control Plane could not answer a fail-closed scope check. */
public final class ManagerScopeUnavailableException extends RuntimeException {
    public ManagerScopeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
