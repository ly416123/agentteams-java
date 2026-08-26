package io.agentteams.manager;

/** The quota dependency could not answer; callers may retry without changing the request. */
public final class ModelCallAdmissionTemporaryFailureException extends RuntimeException {
    public ModelCallAdmissionTemporaryFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
