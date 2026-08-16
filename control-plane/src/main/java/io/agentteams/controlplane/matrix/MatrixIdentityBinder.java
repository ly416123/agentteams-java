package io.agentteams.controlplane.matrix;

import java.util.Optional;

@FunctionalInterface
public interface MatrixIdentityBinder {
    Optional<MatrixIdentity> bind(String matrixUserId);
}
