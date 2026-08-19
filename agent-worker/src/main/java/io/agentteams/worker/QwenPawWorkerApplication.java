package io.agentteams.worker;

/** Starts the long-running QwenPaw AgentTeams Worker process. */
public final class QwenPawWorkerApplication {
    private QwenPawWorkerApplication() { }

    public static void main(String[] args) throws Exception {
        QwenPawWorker worker = QwenPawWorker.fromEnvironment();
        Runtime.getRuntime().addShutdownHook(new Thread(worker::close, "qwenpaw-worker-shutdown"));
        worker.start();
        worker.awaitTermination();
    }
}
