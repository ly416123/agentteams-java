package io.agentteams.controlplane.security;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.Objects;
import java.util.Set;

/** Reads Kubernetes Secret metadata and key names without reading decoded values. */
@FunctionalInterface
public interface KubernetesSecretMetadataReader {

    Metadata read(String namespace, String name);

    record Metadata(Set<String> keys, String resourceVersion, String generation) {
        public Metadata {
            keys = Set.copyOf(Objects.requireNonNull(keys, "keys"));
            resourceVersion = resourceVersion == null ? "" : resourceVersion;
            generation = generation == null ? "" : generation;
        }
    }

    static KubernetesSecretMetadataReader kubernetes(KubernetesClient client) {
        Objects.requireNonNull(client, "client");
        return (namespace, name) -> {
            Secret secret = client.secrets().inNamespace(namespace).withName(name).get();
            if (secret == null) {
                return null;
            }
            Set<String> keys = secret.getData() == null ? Set.of() : secret.getData().keySet();
            String resourceVersion = secret.getMetadata() == null ? ""
                    : secret.getMetadata().getResourceVersion();
            String generation = secret.getMetadata() == null ? ""
                    : text(secret.getMetadata().getGeneration());
            return new Metadata(keys, resourceVersion, generation);
        };
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
