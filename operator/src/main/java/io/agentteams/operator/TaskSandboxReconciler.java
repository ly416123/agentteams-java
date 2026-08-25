package io.agentteams.operator;

import io.agentteams.application.api.SandboxProfile;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.time.Duration;
import java.util.Map;

@ControllerConfiguration
public final class TaskSandboxReconciler implements Reconciler<TaskSandbox> {
    private final KubernetesClient client;
    private final TaskSandboxResourceFactory resources;

    public TaskSandboxReconciler(KubernetesClient client) {
        this(client, new TaskSandboxResourceFactory(runtimeClasses()));
    }

    TaskSandboxReconciler(KubernetesClient client, TaskSandboxResourceFactory resources) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.resources = java.util.Objects.requireNonNull(resources, "resources");
    }

    @Override
    public UpdateControl<TaskSandbox> reconcile(TaskSandbox resource, Context<TaskSandbox> context) {
        Job desired = resources.job(resource);
        String namespace = resource.getMetadata().getNamespace() == null
                ? "default" : resource.getMetadata().getNamespace();
        client.batch().jobs().inNamespace(namespace).resource(desired).createOrReplace();
        Job observed = client.batch().jobs().inNamespace(namespace).withName(desired.getMetadata().getName()).get();

        TaskSandboxStatus status = new TaskSandboxStatus();
        status.setObservedGeneration(resource.getMetadata().getGeneration());
        status.setProviderSandboxId(observed == null || observed.getMetadata() == null
                ? null : observed.getMetadata().getUid());
        status.setEndpointRef(desired.getMetadata().getName());
        if (observed != null && observed.getStatus() != null && observed.getStatus().getFailed() != null
                && observed.getStatus().getFailed() > 0) {
            status.setPhase("FAILED");
            status.setMessage("Sandbox Job failed");
        } else if (observed != null && observed.getStatus() != null
                && observed.getStatus().getSucceeded() != null && observed.getStatus().getSucceeded() > 0) {
            status.setPhase("DESTROYED");
            status.setMessage("Sandbox Job completed");
        } else if (observed != null && observed.getStatus() != null
                && observed.getStatus().getActive() != null && observed.getStatus().getActive() > 0) {
            status.setPhase("READY");
            status.setMessage("Sandbox Job is running");
        } else {
            status.setPhase("PROVISIONING");
            status.setMessage("Waiting for Sandbox Job to start");
        }
        resource.setStatus(status);
        return UpdateControl.updateStatus(resource).rescheduleAfter(Duration.ofSeconds(10));
    }

    static Map<SandboxProfile, String> runtimeClasses() {
        return Map.of(
                SandboxProfile.ISOLATED, textOrDefault(System.getenv("AGENTTEAMS_SANDBOX_RUNTIMECLASS_ISOLATED"),
                        "gvisor"),
                SandboxProfile.HARDENED, textOrDefault(System.getenv("AGENTTEAMS_SANDBOX_RUNTIMECLASS_HARDENED"),
                        "kata-qemu"));
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
