package io.agentteams.controlplane.matrix;

import java.util.Optional;
import java.util.UUID;

public interface MatrixChannelBindingRepository {
    Optional<MatrixChannelBinding> findById(UUID id);
}
