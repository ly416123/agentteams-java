package io.agentteams.operator;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;

@ControllerConfiguration
public final class WorkerReconciler implements Reconciler<Worker> {
    private final KubernetesClient client;

    public WorkerReconciler(KubernetesClient client) {
        this.client = java.util.Objects.requireNonNull(client, "client");
    }

    @Override
    public UpdateControl<Worker> reconcile(Worker resource, Context<Worker> context) {
        String namespace = resource.getMetadata().getNamespace() == null
                ? "default" : resource.getMetadata().getNamespace();
        String name = resource.getMetadata().getName();
        client.apps().deployments().inNamespace(namespace)
                .resource(WorkerResourceFactory.deployment(resource)).createOrReplace();
        client.services().inNamespace(namespace)
                .resource(WorkerResourceFactory.service(resource)).createOrReplace();

        Deployment deployment = client.apps().deployments().inNamespace(namespace).withName(name).get();
        int ready = deployment == null || deployment.getStatus() == null
                || deployment.getStatus().getReadyReplicas() == null
                ? 0 : deployment.getStatus().getReadyReplicas();
        WorkerStatus status = new WorkerStatus();
        status.setReadyReplicas(ready);
        status.setObservedGeneration(resource.getMetadata().getGeneration());
        status.setPhase(ready >= resource.getSpec().replicas() ? "Ready" : "Progressing");
        status.setMessage(ready >= resource.getSpec().replicas()
                ? "Worker deployment is ready" : "Waiting for Worker deployment readiness");
        resource.setStatus(status);
        return UpdateControl.patchStatus(resource);
    }
}
