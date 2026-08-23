package io.agentteams.runtime;

/** Signals that a runtime model call must not be sent to the provider. */
public final class RuntimeModelCallAdmissionRejectedException extends RuntimeException {
    public RuntimeModelCallAdmissionRejectedException(String message) {
        super(message);
    }

    public RuntimeModelCallAdmissionRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
