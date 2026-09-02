package io.agentteams.controlplane.security;

import java.util.Objects;
import java.util.UUID;

public record ExternalIdentity(UUID internalUserId, Status status, String externalOrganizationId, String externalUserId) {
    public enum Status { ACTIVE, DISABLED }

    public ExternalIdentity {
        Objects.requireNonNull(internalUserId, "internalUserId");
        Objects.requireNonNull(status, "status");
        if (externalOrganizationId == null || externalOrganizationId.isBlank()) {
            throw new IllegalArgumentException("externalOrganizationId is required");
        }
        if (externalUserId == null || externalUserId.isBlank()) {
            throw new IllegalArgumentException("externalUserId is required");
        }
    }
}
