package io.agentteams.controlplane.matrix;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.List;

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

    public List<MatrixRoomBinding> list(String tenantId, MatrixRoomContextType contextType, UUID contextId) {
        return repository.findByTenant(tenantId, contextType, contextId);
    }

    public MatrixRoomBinding migrate(String tenantId, UUID bindingId, MatrixRoomContextType contextType,
            UUID contextId, UUID projectId, UUID teamId, long expectedVersion, String actor) {
        MatrixRoomBinding current = require(tenantId, bindingId);
        if (current.status() != MatrixRoomBindingStatus.ACTIVE) {
            throw new IllegalStateException("only active Matrix room bindings can migrate");
        }
        if (!Objects.equals(current.projectId(), projectId) || !Objects.equals(current.teamId(), teamId)) {
            throw new IllegalArgumentException("Matrix room migration must preserve project and team scope");
        }
        Instant now = clock.instant();
        MatrixRoomBinding next = new MatrixRoomBinding(UUID.randomUUID(), current.organizationId(), current.tenantId(),
                current.roomId(), contextType, contextId, projectId, teamId, MatrixRoomBindingStatus.ACTIVE, actor, now,
                now, 0);
        if (repository.findActiveByContext(tenantId, contextType, contextId).isPresent()) {
            throw new IllegalStateException("Matrix context already has an active binding");
        }
        repository.update(withStatus(current, MatrixRoomBindingStatus.REVOKED, now, expectedVersion), expectedVersion);
        return repository.save(next);
    }

    public MatrixRoomBinding revoke(String tenantId, UUID bindingId, long expectedVersion, String actor) {
        MatrixRoomBinding current = require(tenantId, bindingId);
        if (current.status() == MatrixRoomBindingStatus.REVOKED) return current;
        return repository.update(withStatus(current, MatrixRoomBindingStatus.REVOKED, clock.instant(), expectedVersion),
                expectedVersion);
    }

    private MatrixRoomBinding require(String tenantId, UUID id) {
        return repository.findById(tenantId, id)
                .orElseThrow(() -> new IllegalArgumentException("Matrix room binding not found"));
    }

    private static MatrixRoomBinding withStatus(MatrixRoomBinding current, MatrixRoomBindingStatus status,
            Instant updatedAt, long expectedVersion) {
        return new MatrixRoomBinding(current.id(), current.organizationId(), current.tenantId(), current.roomId(),
                current.primaryContextType(), current.primaryContextId(), current.projectId(), current.teamId(), status,
                current.createdBy(), current.createdAt(), updatedAt, expectedVersion + 1);
    }
}
