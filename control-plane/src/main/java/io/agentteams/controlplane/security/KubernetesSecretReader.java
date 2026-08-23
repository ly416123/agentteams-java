package io.agentteams.controlplane.security;

/** Reads only the presence state of a Kubernetes Secret data key. */
@FunctionalInterface
public interface KubernetesSecretReader {

    ValueState read(String namespace, String name, String key);

    enum ValueState {
        MISSING,
        PRESENT
    }
}
