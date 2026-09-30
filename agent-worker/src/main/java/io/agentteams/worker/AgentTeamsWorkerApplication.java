package io.agentteams.worker;

/** Starts the long-running AgentScope AgentTeams Worker process. */
public final class AgentTeamsWorkerApplication {
    private AgentTeamsWorkerApplication() { }

    public static void main(String[] args) throws Exception {
        AgentTeamsWorker worker = AgentTeamsWorker.fromEnvironment();
        Runtime.getRuntime().addShutdownHook(new Thread(worker::close, "agentteams-worker-shutdown"));
        worker.start();
        worker.awaitTermination();
    }
}
