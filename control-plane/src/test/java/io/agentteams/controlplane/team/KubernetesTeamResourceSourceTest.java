package io.agentteams.controlplane.team;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KubernetesTeamResourceSourceTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void startsNamespacedInformerAndDelegatesAddAndDelete() {
        KubernetesClient client = org.mockito.Mockito.mock(KubernetesClient.class);
        TeamCrdSynchronizer synchronizer = org.mockito.Mockito.mock(TeamCrdSynchronizer.class);
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                Resource<GenericKubernetesResource>> operation = org.mockito.Mockito.mock(MixedOperation.class);
        NonNamespaceOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                Resource<GenericKubernetesResource>> namespaced = org.mockito.Mockito.mock(NonNamespaceOperation.class);
        SharedIndexInformer<GenericKubernetesResource> informer = org.mockito.Mockito.mock(SharedIndexInformer.class);
        AtomicReference<ResourceEventHandler<GenericKubernetesResource>> handler = new AtomicReference<>();

        when(client.genericKubernetesResources(any())).thenReturn(operation);
        when(operation.inNamespace("agentteams")).thenReturn(namespaced);
        when(namespaced.inform(any(ResourceEventHandler.class))).thenAnswer(invocation -> {
            handler.set(invocation.getArgument(0));
            return informer;
        });

        KubernetesTeamResourceSource source = new KubernetesTeamResourceSource(client, synchronizer, "agentteams");
        GenericKubernetesResource resource = new GenericKubernetesResource();
        source.start();

        handler.get().onAdd(resource);
        handler.get().onDelete(resource, false);
        source.close();

        verify(informer).start();
        verify(informer).close();
        verify(synchronizer).apply(resource);
        verify(synchronizer).delete(resource);
    }
}
