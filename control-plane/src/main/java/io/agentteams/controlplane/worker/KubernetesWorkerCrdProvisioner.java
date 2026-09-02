package io.agentteams.controlplane.worker;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Applies a namespaced Worker CR for an explicitly instantiated logical Worker. */
public final class KubernetesWorkerCrdProvisioner implements WorkerCrdProvisioner {
    private static final String FIELD_MANAGER = "agentteams-control-plane";
    private static final ResourceDefinitionContext WORKER_CONTEXT = new ResourceDefinitionContext.Builder()
            .withGroup("agentteams.io").withVersion("v1alpha1").withKind("Worker")
            .withPlural("workers").withNamespaced(true).build();

    private final KubernetesClient client;
    private final String namespace;

    public KubernetesWorkerCrdProvisioner(KubernetesClient client, String namespace) {
        this.client = Objects.requireNonNull(client, "client");
        this.namespace = required(namespace, "namespace");
    }

    @Override
    public void provision(Request request) {
        Objects.requireNonNull(request, "request");
        String name = resourceName(request.workerId());
        GenericKubernetesResource existing = client.genericKubernetesResources(WORKER_CONTEXT)
                .inNamespace(namespace).withName(name).get();
        if (existing != null) {
            assertSame(existing, request);
            return;
        }
        try {
            client.resource(resource(namespace, request)).inNamespace(namespace).fieldManager(FIELD_MANAGER).serverSideApply();
        } catch (KubernetesClientException error) {
            if (error.getCode() != 409) throw error;
            GenericKubernetesResource winner = client.genericKubernetesResources(WORKER_CONTEXT)
                    .inNamespace(namespace).withName(name).get();
            if (winner == null) throw error;
            assertSame(winner, request);
        }
    }

    static String resourceName(java.util.UUID workerId) {
        return "worker-" + workerId.toString().replace("-", "");
    }

    static GenericKubernetesResource resource(String namespace, Request request) {
        GenericKubernetesResource resource = new GenericKubernetesResource();
        resource.setApiVersion("agentteams.io/v1alpha1");
        resource.setKind("Worker");
        resource.setMetadata(new ObjectMetaBuilder().withName(resourceName(request.workerId()))
                .withNamespace(namespace).withLabels(labels(request)).build());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("agentId", request.workerId().toString());
        spec.put("runtime", request.runtime());
        spec.put("image", request.image());
        spec.put("replicas", request.replicas());
        spec.put("env", environment(request));
        if (request.tlsSecret() != null && !request.tlsSecret().isBlank()) {
            spec.put("tlsSecret", request.tlsSecret().trim());
        }
        if (request.specDigest() != null && !request.specDigest().isBlank()) {
            spec.put("specDigest", request.specDigest().trim());
        }
        if (request.configRevision() != null && !request.configRevision().isBlank()) {
            spec.put("configRevision", request.configRevision().trim());
        }
        if (request.secretGeneration() != null && !request.secretGeneration().isBlank()) {
            spec.put("secretGeneration", request.secretGeneration().trim());
        }
        resource.setAdditionalProperty("spec", spec);
        return resource;
    }

    private static Map<String, String> environment(Request request) {
        Map<String, String> environment = new LinkedHashMap<>(request.environment());
        environment.putIfAbsent("AGENTTEAMS_GATEWAY_HOST", request.gatewayHost());
        environment.putIfAbsent("AGENTTEAMS_GATEWAY_PORT", Integer.toString(request.gatewayPort()));
        environment.putIfAbsent("AGENTTEAMS_CONFIG_MANIFEST_BASE_URL", request.configManifestBaseUrl());
        environment.putIfAbsent("QWENPAW_ENDPOINT", request.qwenPawEndpoint());
        environment.putIfAbsent("AGENTTEAMS_MODEL_PROVIDER", request.modelProvider());
        environment.putIfAbsent("AGENTTEAMS_MODEL", request.model());
        environment.putIfAbsent("AGENTTEAMS_SCOPE_TENANT", request.tenantId());
        environment.putIfAbsent("AGENTTEAMS_SCOPE_PROJECT", request.projectId());
        environment.putIfAbsent("AGENTTEAMS_RUNTIME", request.runtime());
        return environment;
    }

    private static Map<String, String> labels(Request request) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app.kubernetes.io/name", "agentteams-worker");
        labels.put("app.kubernetes.io/managed-by", "agentteams-control-plane");
        labels.put("agentteams.io/agent-id", request.workerId().toString());
        labels.put("agentteams.io/tenant", request.tenantId());
        labels.put("agentteams.io/project", request.projectId());
        labels.put("agentteams.io/team", request.team());
        return labels;
    }

    private static void assertSame(GenericKubernetesResource existing, Request request) {
        Object raw = existing.get("spec");
        if (!(raw instanceof Map<?, ?> spec)
                || !request.workerId().toString().equals(String.valueOf(spec.get("agentId")))
                || !request.runtime().equals(String.valueOf(spec.get("runtime")))
                || !request.image().equals(String.valueOf(spec.get("image")))) {
            throw new IllegalStateException("Worker CR conflicts with the requested Worker");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
