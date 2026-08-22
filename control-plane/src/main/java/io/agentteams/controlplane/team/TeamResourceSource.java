package io.agentteams.controlplane.team;

public interface TeamResourceSource extends AutoCloseable {
    void start();

    @Override
    void close();
}
