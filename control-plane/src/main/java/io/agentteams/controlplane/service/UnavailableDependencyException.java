package io.agentteams.controlplane.service;

public final class UnavailableDependencyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnavailableDependencyException(String dependency, Throwable cause) {
        super(dependency + " is unavailable", cause);
    }
}
