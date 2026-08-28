package io.agentteams.controlplane.service;

public final class ModelPriceSyncException extends RuntimeException {
    public ModelPriceSyncException(String message) { super(message); }
    public ModelPriceSyncException(String message, Throwable cause) { super(message, cause); }
}
