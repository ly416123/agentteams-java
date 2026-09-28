package io.agentteams.controlplane.matrix;

public final class MatrixRoomNotBoundException extends RuntimeException {
    public MatrixRoomNotBoundException(String roomId) {
        super("Matrix room is not bound: " + roomId);
    }
}
