package io.agentteams.controlplane.service;

import java.util.UUID;

public final class ResourceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String resourceType, UUID id) {
        super(resourceType + " " + id + " was not found");
    }

    public ResourceNotFoundException(String resourceType) {
        super(resourceType + " was not found");
    }
}
