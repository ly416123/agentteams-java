package io.agentteams.manager;

/** Raised when a model call cannot reserve the configured project capacity. */
public final class ModelCallAdmissionRejectedException extends RuntimeException {
    public ModelCallAdmissionRejectedException(String message) {
        super(message);
    }

    public ModelCallAdmissionRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
