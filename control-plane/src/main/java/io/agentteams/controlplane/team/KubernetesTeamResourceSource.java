package io.agentteams.controlplane.team;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KubernetesTeamResourceSource implements TeamResourceSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesTeamResourceSource.class);
    private static final ResourceDefinitionContext TEAM_CONTEXT = new ResourceDefinitionContext.Builder()
            .withGroup("agentteams.io")
            .withVersion("v1alpha1")
            .withPlural("teams")
            .withNamespaced(true)
            .build();

    private final KubernetesClient client;
    private final TeamCrdSynchronizer synchronizer;
    private final String namespace;
    private SharedIndexInformer<GenericKubernetesResource> informer;

    public KubernetesTeamResourceSource(KubernetesClient client, TeamCrdSynchronizer synchronizer, String namespace) {
        this.client = Objects.requireNonNull(client, "client");
        this.synchronizer = Objects.requireNonNull(synchronizer, "synchronizer");
        this.namespace = namespace == null ? "" : namespace.trim();
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized void start() {
        if (informer != null) {
            return;
        }
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                Resource<GenericKubernetesResource>> operation = client.genericKubernetesResources(TEAM_CONTEXT);
        NonNamespaceOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                Resource<GenericKubernetesResource>> scoped = namespace.isBlank()
                        ? operation : operation.inNamespace(namespace);
        informer = scoped.inform(new ResourceEventHandler<>() {
            @Override
            public void onAdd(GenericKubernetesResource resource) {
                apply("add", resource);
            }

            @Override
            public void onUpdate(GenericKubernetesResource oldResource, GenericKubernetesResource newResource) {
                apply("update", newResource);
            }

            @Override
            public void onDelete(GenericKubernetesResource resource, boolean deletedFinalStateUnknown) {
                try {
                    synchronizer.delete(resource);
                } catch (RuntimeException error) {
                    logFailure("delete", resource, error);
                }
            }

            private void apply(String event, GenericKubernetesResource resource) {
                try {
                    synchronizer.apply(resource);
                } catch (RuntimeException error) {
                    logFailure(event, resource, error);
                }
            }

            private void logFailure(String event, GenericKubernetesResource resource, RuntimeException error) {
                String resourceName = resource == null || resource.getMetadata() == null
                        ? "unknown" : resource.getMetadata().getName();
                String resourceVersion = resource == null || resource.getMetadata() == null
                        ? "unknown" : resource.getMetadata().getResourceVersion();
                LOGGER.warn("Team CRD sync failed event={} namespace={} name={} resourceVersion={} reason={}",
                        event, namespace, resourceName, resourceVersion, error.getMessage());
            }
        });
        informer.start();
    }

    @Override
    public synchronized void close() {
        if (informer != null) {
            informer.close();
            informer = null;
        }
    }
}
