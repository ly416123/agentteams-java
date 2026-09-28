package io.agentteams.controlplane.matrix;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A tenant-scoped Matrix room binding with one authoritative primary context. */
public record MatrixRoomBinding(UUID id, String organizationId, String tenantId, String roomId,
        MatrixRoomContextType primaryContextType, UUID primaryContextId, UUID projectId, UUID teamId,
        MatrixRoomBindingStatus status, String createdBy, Instant createdAt, Instant updatedAt, long version) {
    public MatrixRoomBinding {
        Objects.requireNonNull(id, "id");
        organizationId = required(organizationId, "organizationId");
        tenantId = required(tenantId, "tenantId");
        roomId = required(roomId, "roomId");
        Objects.requireNonNull(primaryContextType, "primaryContextType");
        Objects.requireNonNull(primaryContextId, "primaryContextId");
        Objects.requireNonNull(status, "status");
        createdBy = required(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        validateContext(primaryContextType, primaryContextId, projectId, teamId);
    }

    public MatrixRoomBinding withStatus(MatrixRoomBindingStatus nextStatus) {
        return new MatrixRoomBinding(id, organizationId, tenantId, roomId, primaryContextType, primaryContextId,
                projectId, teamId, nextStatus, createdBy, createdAt, updatedAt, version);
    }

    private static void validateContext(MatrixRoomContextType type, UUID contextId, UUID projectId, UUID teamId) {
        switch (type) {
            case PROJECT -> {
                if (!contextId.equals(projectId)) throw new IllegalArgumentException(
                        "project binding must use its project as primary context");
            }
            case TEAM -> {
                if (projectId == null || !contextId.equals(teamId)) throw new IllegalArgumentException(
                        "team binding must include its project and team as primary context");
            }
            case CONVERSATION, TASK -> {
                if (projectId == null || teamId == null) throw new IllegalArgumentException(
                        "conversation and task bindings must include project and team scope");
            }
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
