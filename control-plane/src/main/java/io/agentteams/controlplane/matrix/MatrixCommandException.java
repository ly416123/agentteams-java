package io.agentteams.controlplane.matrix;

public final class MatrixCommandException extends IllegalArgumentException {
    public MatrixCommandException(String message) {
        super(message);
    }

    public MatrixCommandException(String message, Throwable cause) {
        super(message, cause);
    }
}
