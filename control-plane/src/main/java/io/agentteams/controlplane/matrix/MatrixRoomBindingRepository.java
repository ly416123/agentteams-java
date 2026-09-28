package io.agentteams.controlplane.matrix;

import java.util.Optional;
import java.util.UUID;

public interface MatrixRoomBindingRepository {
    Optional<MatrixRoomBinding> findActiveByRoom(String tenantId, String roomId);

    Optional<MatrixRoomBinding> findActiveByContext(String tenantId, MatrixRoomContextType contextType,
            UUID contextId);

    MatrixRoomBinding save(MatrixRoomBinding binding);
}
