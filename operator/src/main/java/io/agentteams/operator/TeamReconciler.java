package io.agentteams.operator;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;

@ControllerConfiguration
public final class TeamReconciler implements Reconciler<Team> {
    private final KubernetesClient client;

    public TeamReconciler(KubernetesClient client) { this.client = java.util.Objects.requireNonNull(client, "client"); }

    @Override
    public UpdateControl<Team> reconcile(Team resource, Context<Team> context) {
        String namespace = resource.getMetadata().getNamespace() == null ? "default" : resource.getMetadata().getNamespace();
        client.configMaps().inNamespace(namespace).resource(TeamResourceFactory.configMap(resource)).createOrReplace();
        TeamStatus status = new TeamStatus();
        status.setPhase("Applied");
        status.setObservedGeneration(resource.getMetadata().getGeneration());
        status.setMessage("Team configuration projected");
        resource.setStatus(status);
        return UpdateControl.patchStatus(resource);
    }
}
