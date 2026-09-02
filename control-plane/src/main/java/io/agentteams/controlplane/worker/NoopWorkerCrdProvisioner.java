package io.agentteams.controlplane.worker;

/** Safe default for deployments that do not grant the Control Plane Kubernetes write permission. */
public final class NoopWorkerCrdProvisioner implements WorkerCrdProvisioner {
    @Override
    public void provision(Request request) {
        // Logical Worker creation remains usable for API-only installations.
    }
}
