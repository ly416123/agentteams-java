package io.agentteams.controlplane.matrix;

public final class MatrixCommandHandlingException extends RuntimeException {
    public MatrixCommandHandlingException(String message) {
        super(message);
    }

    public MatrixCommandHandlingException(String message, Throwable cause) {
        super(message, cause);
    }
}
