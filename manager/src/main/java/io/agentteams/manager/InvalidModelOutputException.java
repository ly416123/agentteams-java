package io.agentteams.manager;

public class InvalidModelOutputException extends RuntimeException {
    public InvalidModelOutputException(String message, Throwable cause) { super(message, cause); }
}
