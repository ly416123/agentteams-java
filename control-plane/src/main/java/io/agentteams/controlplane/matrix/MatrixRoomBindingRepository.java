package io.agentteams.controlplane.matrix;

import java.util.Optional;
import java.util.List;
import java.time.Instant;
import java.util.UUID;

public interface MatrixRoomBindingRepository {
    Optional<MatrixRoomBinding> findActiveByRoom(String tenantId, String roomId);

    Optional<MatrixRoomBinding> findActiveByContext(String tenantId, MatrixRoomContextType contextType,
            UUID contextId);

    List<MatrixRoomBinding> findByTenant(String tenantId, MatrixRoomContextType contextType, UUID contextId);

    Optional<MatrixRoomBinding> findById(String tenantId, UUID id);

    MatrixRoomBinding save(MatrixRoomBinding binding);

    MatrixRoomBinding update(MatrixRoomBinding binding, long expectedVersion);
}
