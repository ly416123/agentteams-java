package io.agentteams.domain.task;

/** Base type for errors raised by the framework-independent domain model. */
public class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DomainException(String message) {
        super(message);
    }
}
