package io.agentteams.runtime;

public final class QwenPawProcessException extends RuntimeException {
    public QwenPawProcessException(String message, Throwable cause) {
        super(message, cause);
    }

    public QwenPawProcessException(String message) {
        super(message);
    }
}
