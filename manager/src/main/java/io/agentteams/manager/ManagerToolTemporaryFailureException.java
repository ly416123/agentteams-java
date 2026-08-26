package io.agentteams.manager;

public final class ManagerToolTemporaryFailureException extends RuntimeException {
    public ManagerToolTemporaryFailureException(String message, Throwable cause) { super(message, cause); }
    public ManagerToolTemporaryFailureException(String message) { super(message); }
}
