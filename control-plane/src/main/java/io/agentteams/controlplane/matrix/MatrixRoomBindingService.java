package io.agentteams.controlplane.matrix;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Creates the single active primary binding for a Matrix room and context. */
public final class MatrixRoomBindingService {
    private final MatrixRoomBindingRepository repository;
    private final Clock clock;

    public MatrixRoomBindingService(MatrixRoomBindingRepository repository) {
        this(repository, Clock.systemUTC());
    }

    MatrixRoomBindingService(MatrixRoomBindingRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public MatrixRoomBinding bind(String organizationId, String tenantId, String roomId,
            MatrixRoomContextType contextType, UUID contextId, UUID projectId, UUID teamId, String actor) {
        if (contextType == null) throw new IllegalArgumentException("contextType must not be null");
        if (contextId == null) throw new IllegalArgumentException("contextId must not be null");
        Instant now = clock.instant();
        MatrixRoomBinding binding = new MatrixRoomBinding(UUID.randomUUID(), organizationId, tenantId, roomId,
                contextType, contextId, projectId, teamId, MatrixRoomBindingStatus.ACTIVE, actor, now, now, 0);
        if (repository.findActiveByRoom(tenantId, roomId).isPresent()) {
            throw new IllegalStateException("Matrix room already has an active binding");
        }
        if (repository.findActiveByContext(tenantId, contextType, contextId).isPresent()) {
            throw new IllegalStateException("Matrix context already has an active binding");
        }
        return repository.save(binding);
    }
}
