package io.agentteams.controlplane.matrix;

@FunctionalInterface
public interface MatrixCommandHandler {
    String handle(String sender, MatrixCommand command);

    default String handle(MatrixIdentity identity, MatrixCommand command) {
        return handle(identity.matrixUserId(), command);
    }
}
