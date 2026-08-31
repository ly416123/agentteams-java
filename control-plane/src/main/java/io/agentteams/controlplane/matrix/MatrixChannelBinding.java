package io.agentteams.controlplane.matrix;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Tenant-scoped Matrix room binding used by the generic outbound Channel port. */
public record MatrixChannelBinding(UUID id, String organizationId, String tenantId, String projectId, String roomId,
        Set<String> eventTypes, boolean enabled) {
    public MatrixChannelBinding {
        Objects.requireNonNull(id, "id");
        organizationId = required(organizationId, "organizationId");
        tenantId = required(tenantId, "tenantId");
        projectId = required(projectId, "projectId");
        roomId = required(roomId, "roomId");
        eventTypes = Set.copyOf(Objects.requireNonNull(eventTypes, "eventTypes"));
        if (eventTypes.isEmpty()) throw new IllegalArgumentException("eventTypes must not be empty");
    }

    public boolean matchesScope(String organizationId, String tenantId, String projectId) {
        return this.organizationId.equals(organizationId) && this.tenantId.equals(tenantId)
                && this.projectId.equals(projectId);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
