package io.agentteams.controlplane.security;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads only ExternalSecret status and target metadata, never Secret values. */
@FunctionalInterface
public interface ExternalSecretStatusReader {

    ExternalSecretStatus read(String namespace, String name);

    static ExternalSecretStatusReader kubernetes(KubernetesClient client) {
        Objects.requireNonNull(client, "client");
        return (namespace, name) -> {
            ResourceDefinitionContext context = new ResourceDefinitionContext.Builder()
                    .withGroup("external-secrets.io").withVersion("v1beta1")
                    .withPlural("externalsecrets").withNamespaced(true).build();
            MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                    Resource<GenericKubernetesResource>> operation = client.genericKubernetesResources(context);
            NonNamespaceOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                    Resource<GenericKubernetesResource>> scoped = operation.inNamespace(namespace);
            GenericKubernetesResource resource = scoped.withName(name).get();
            if (resource == null) {
                return new ExternalSecretStatus(ExternalSecretStatus.State.NOT_FOUND, "", "");
            }
            Map<String, Object> status = map(resource.get("status"));
            List<?> conditions = list(status.get("conditions"));
            boolean readyCondition = conditions.stream().map(ExternalSecretStatusReader::map)
                    .anyMatch(condition -> "Ready".equals(condition.get("type"))
                            && "True".equalsIgnoreCase(String.valueOf(condition.get("status"))));
            Map<String, Object> spec = map(resource.get("spec"));
            Map<String, Object> target = map(spec.get("target"));
            String targetName = text(target.get("name"));
            String generation = resource.getMetadata() == null
                    ? "" : text(resource.getMetadata().getGeneration());
            String observedGeneration = text(status.get("observedGeneration"));
            boolean ready = readyCondition && new ExternalSecretStatus(
                    ExternalSecretStatus.State.READY, targetName, generation, observedGeneration)
                    .generationIsCurrent();
            return new ExternalSecretStatus(ready ? ExternalSecretStatus.State.READY
                    : ExternalSecretStatus.State.NOT_READY, targetName, generation, observedGeneration);
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
