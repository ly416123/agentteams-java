package io.agentteams.operator;

import io.agentteams.application.api.SandboxProfile;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@ControllerConfiguration
public final class TaskSandboxReconciler implements Reconciler<TaskSandbox> {
    static final String FINALIZER = "agentteams.io/task-sandbox-cleanup";
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
        String namespace = resource.getMetadata().getNamespace() == null
                ? "default" : resource.getMetadata().getNamespace();
        String jobName = resource.getMetadata().getName() + "-job";
        Job observed = client.batch().jobs().inNamespace(namespace).withName(jobName).get();

        if (resource.getMetadata().getDeletionTimestamp() != null
                || (resource.getSpec() != null && resource.getSpec().terminationRequested())) {
            return terminate(resource, namespace, jobName, observed);
        }
        boolean finalizerAdded = ensureFinalizer(resource);
        Job desired = resources.job(resource);
        String generation = desired.getMetadata().getLabels().get("agentteams.io/task-sandbox-generation");
        Map<String, String> observedLabels = observed == null || observed.getMetadata() == null
                ? Map.of() : observed.getMetadata().getLabels();
        if (observed != null && !generation.equals(observedLabels == null ? null
                : observedLabels.get("agentteams.io/task-sandbox-generation"))) {
            client.batch().jobs().inNamespace(namespace).withName(jobName).delete();
            return status(resource, "PROVISIONING", "Replacing stale Sandbox Job generation", finalizerAdded);
        }
        if (observed == null) {
            client.batch().jobs().inNamespace(namespace).resource(desired).create();
            observed = desired;
        }

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
        UpdateControl<TaskSandbox> update = finalizerAdded
                ? UpdateControl.updateResourceAndStatus(resource)
                : UpdateControl.updateStatus(resource);
        return update.rescheduleAfter(Duration.ofSeconds(10));
    }

    private UpdateControl<TaskSandbox> terminate(TaskSandbox resource, String namespace, String jobName, Job observed) {
        if (observed != null) {
            client.batch().jobs().inNamespace(namespace).withName(jobName).delete();
            return status(resource, "STOPPING", "Sandbox Job termination requested", false);
        }
        TaskSandboxStatus status = new TaskSandboxStatus();
        status.setObservedGeneration(resource.getMetadata().getGeneration());
        status.setPhase("DESTROYED");
        status.setEndpointRef(jobName);
        status.setMessage("Sandbox Job is absent");
        resource.setStatus(status);
        if (resource.getMetadata().getDeletionTimestamp() != null) {
            List<String> finalizers = new ArrayList<>(resource.getMetadata().getFinalizers() == null
                    ? List.of() : resource.getMetadata().getFinalizers());
            finalizers.remove(FINALIZER);
            resource.getMetadata().setFinalizers(finalizers);
            return UpdateControl.updateResourceAndStatus(resource);
        }
        return UpdateControl.updateStatus(resource);
    }

    private UpdateControl<TaskSandbox> status(TaskSandbox resource, String phase, String message,
            boolean updateResource) {
        TaskSandboxStatus status = new TaskSandboxStatus();
        status.setObservedGeneration(resource.getMetadata().getGeneration());
        status.setPhase(phase);
        status.setEndpointRef(resource.getMetadata().getName() + "-job");
        status.setMessage(message);
        resource.setStatus(status);
        UpdateControl<TaskSandbox> update = updateResource
                ? UpdateControl.updateResourceAndStatus(resource)
                : UpdateControl.updateStatus(resource);
        return update.rescheduleAfter(Duration.ofSeconds(2));
    }

    private boolean ensureFinalizer(TaskSandbox resource) {
        List<String> finalizers = new ArrayList<>(resource.getMetadata().getFinalizers() == null
                ? List.of() : resource.getMetadata().getFinalizers());
        if (finalizers.contains(FINALIZER)) {
            return false;
        }
        finalizers.add(FINALIZER);
        resource.getMetadata().setFinalizers(finalizers);
        return true;
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
