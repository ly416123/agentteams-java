package io.agentteams.controlplane.matrix;

/** Safe boundary error for an unavailable or corrupt identity mapping store. */
public final class MatrixIdentityServiceException extends RuntimeException {
    public MatrixIdentityServiceException(String message, Throwable cause) { super(message, cause); }
}
