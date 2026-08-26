package io.agentteams.controlplane.sandbox;

import io.agentteams.application.api.SandboxFailure;
import io.agentteams.application.api.SandboxFailureCategory;
import io.agentteams.application.api.SandboxObservation;
import io.agentteams.application.api.SandboxProvisionCommand;
import io.agentteams.application.api.SandboxProvisionReceipt;
import io.agentteams.application.api.SandboxProviderException;
import io.agentteams.application.api.SandboxProviderPhase;
import io.agentteams.application.api.SandboxProviderRef;
import io.agentteams.application.api.SandboxRenewCommand;
import io.agentteams.application.api.SandboxRenewReceipt;
import io.agentteams.application.api.SandboxRuntimePort;
import io.agentteams.application.api.SandboxTerminationCommand;
import io.agentteams.application.api.SandboxTerminationReceipt;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Kubernetes-backed asynchronous sandbox provider. It owns only TaskSandbox CRs. */
public final class KubernetesSandboxRuntime implements SandboxRuntimePort {
    private static final String PROVIDER = "kubernetes";
    private static final String FIELD_MANAGER = "agentteams-control-plane";
    private static final ResourceDefinitionContext TASK_SANDBOX_CONTEXT = new ResourceDefinitionContext.Builder()
            .withGroup("agentteams.io").withVersion("v1alpha1").withKind("TaskSandbox")
            .withPlural("tasksandboxes").withNamespaced(true).build();

    private final KubernetesClient client;
    private final String namespace;
    private final Clock clock;
    private final SandboxRuntimeProperties properties;

    public KubernetesSandboxRuntime(KubernetesClient client, String namespace, Clock clock) {
        this(client, namespace, clock, new SandboxRuntimeProperties());
    }

    public KubernetesSandboxRuntime(KubernetesClient client, String namespace, Clock clock,
            SandboxRuntimeProperties properties) {
        this.client = Objects.requireNonNull(client, "client");
        this.namespace = required(namespace, "namespace");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public SandboxProvisionReceipt ensureProvisioned(SandboxProvisionCommand command) {
        Objects.requireNonNull(command, "command");
        String name = resourceName(command.attemptId());
        GenericKubernetesResource existing = get(name);
        if (existing == null) {
            try {
                apply(newResource(name, command));
            } catch (SandboxProviderException error) {
                if (error.category() != SandboxFailureCategory.IDEMPOTENCY_CONFLICT) throw error;
                GenericKubernetesResource winner = get(name);
                if (winner == null) throw error;
                assertSameProvision(winner, command);
                return provisionReceipt(winner, command);
            }
            GenericKubernetesResource created = get(name);
            if (created == null) {
                throw new SandboxProviderException(SandboxFailureCategory.PROVIDER_RESPONSE_INVALID,
                        "TaskSandbox disappeared after apply");
            }
            assertSameProvision(created, command);
            return provisionReceipt(created, command);
        }
        assertSameProvision(existing, command);
        return provisionReceipt(existing, command);
    }

    @Override
    public SandboxObservation inspect(SandboxProviderRef providerRef) {
        validateProvider(providerRef);
        ResourceIdentity identity = identity(providerRef);
        GenericKubernetesResource resource = get(identity.name());
        if (resource == null) {
            return new SandboxObservation(providerRef, SandboxProviderPhase.LOST, null, clock.instant(),
                    0, null, new SandboxFailure(SandboxFailureCategory.PROVIDER_RESOURCE_LOST,
                            "TaskSandbox resource is absent"));
        }
        verifyUid(resource, providerRef);
        Map<String, Object> spec = map(resource.get("spec"));
        Instant expiresAt = instant(spec.get("expiresAt"), clock.instant());
        Map<String, Object> status = map(resource.get("status"));
        SandboxProviderPhase phase = phase(status.get("phase"));
        long generation = longValue(status.get("observedGeneration"),
                longValue(resource.getMetadata() == null ? null : resource.getMetadata().getGeneration(), 0));
        String endpoint = text(status.get("endpointRef"));
        String workloadUid = text(status.get("workloadUid"));
        SandboxFailure failure = failure(status, phase);
        if (phase == SandboxProviderPhase.READY
                && (endpoint == null || workloadUid == null
                || !conditionTrue(status, "runnerReady", "RunnerReady", "Ready")
                || !conditionTrue(status, "healthy", "healthy", "Healthy", "RunnerHealthy"))) {
            phase = SandboxProviderPhase.PROVISIONING;
        }
        return new SandboxObservation(providerRef, phase, endpoint, expiresAt, generation, workloadUid, failure);
    }

    @Override
    public SandboxRenewReceipt ensureExpiry(SandboxRenewCommand command) {
        Objects.requireNonNull(command, "command");
        validateProvider(command.providerRef());
        GenericKubernetesResource resource = resource(command.providerRef());
        Map<String, Object> spec = map(resource.get("spec"));
        Instant currentExpiry = instant(spec.get("expiresAt"), clock.instant());
        SandboxProviderPhase currentPhase = phase(map(resource.get("status")).get("phase"));
        if (currentPhase == SandboxProviderPhase.DESTROYED || currentPhase == SandboxProviderPhase.FAILED
                || currentPhase == SandboxProviderPhase.EXPIRED || currentPhase == SandboxProviderPhase.LOST
                || Boolean.TRUE.equals(spec.get("terminationRequested"))) {
            throw conflict("terminal sandbox cannot be renewed");
        }
        if (command.expiresAt().isBefore(currentExpiry)) {
            throw conflict("requested sandbox expiry cannot shorten the existing expiry");
        }
        if (command.expiresAt().equals(currentExpiry)) {
            return new SandboxRenewReceipt(command.providerRef(), currentPhase, currentExpiry,
                    observedGeneration(resource));
        }
        spec.put("expiresAt", command.expiresAt().toString());
        setSpec(resource, spec);
        GenericKubernetesResource updated = apply(resource);
        return new SandboxRenewReceipt(command.providerRef(), phase(map(updated.get("status")).get("phase")),
                command.expiresAt(), observedGeneration(updated));
    }

    @Override
    public SandboxTerminationReceipt ensureTerminated(SandboxTerminationCommand command) {
        Objects.requireNonNull(command, "command");
        validateProvider(command.providerRef());
        GenericKubernetesResource resource = resource(command.providerRef());
        Map<String, Object> spec = map(resource.get("spec"));
        SandboxProviderPhase currentPhase = phase(map(resource.get("status")).get("phase"));
        String currentReason = text(spec.get("terminationReason"));
        if (currentPhase == SandboxProviderPhase.DESTROYED) {
            return new SandboxTerminationReceipt(command.providerRef(), currentPhase, observedGeneration(resource));
        }
        if (currentReason != null && !currentReason.equals(command.reason().name())) {
            throw conflict("termination reason conflicts with the existing request");
        }
        if (!Boolean.TRUE.equals(spec.get("terminationRequested"))) {
            spec.put("terminationRequested", true);
            spec.put("terminationReason", command.reason().name());
            setSpec(resource, spec);
            resource = apply(resource);
        }
        return new SandboxTerminationReceipt(command.providerRef(), phase(map(resource.get("status")).get("phase")),
                observedGeneration(resource));
    }

    public String resourceName(java.util.UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId");
        return "task-sandbox-" + attemptId.toString().replace("-", "");
    }

    private GenericKubernetesResource newResource(String name, SandboxProvisionCommand command) {
        GenericKubernetesResource resource = new GenericKubernetesResource();
        resource.setApiVersion("agentteams.io/v1alpha1");
        resource.setKind("TaskSandbox");
        resource.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(namespace).build());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("taskId", command.taskId().toString());
        spec.put("attemptId", command.attemptId().toString());
        spec.put("idempotencyKey", command.idempotencyKey());
        spec.put("profile", command.profile().name());
        spec.put("runtimeClassName", properties.runtimeClassName(command.profile()));
        spec.put("image", "ghcr.io/ly416123/agentteams-task-sandbox:latest");
        spec.put("ttlSeconds", (int) command.ttl().toSeconds());
        spec.put("template", command.template());
        spec.put("expiresAt", command.expiresAt().toString());
        spec.put("terminationRequested", false);
        setSpec(resource, spec);
        return resource;
    }

    private SandboxProvisionReceipt provisionReceipt(GenericKubernetesResource resource,
            SandboxProvisionCommand command) {
        String uid = resource.getMetadata() == null ? null : resource.getMetadata().getUid();
        if (uid == null || uid.isBlank()) {
            throw new SandboxProviderException(SandboxFailureCategory.PROVIDER_RESPONSE_INVALID,
                    "TaskSandbox response did not contain a resource UID");
        }
        SandboxProviderRef ref = new SandboxProviderRef(PROVIDER, namespace + "/" + resourceName(command.attemptId()), uid);
        return new SandboxProvisionReceipt(ref, phase(map(resource.get("status")).get("phase")),
                observedGeneration(resource));
    }

    private GenericKubernetesResource resource(SandboxProviderRef providerRef) {
        ResourceIdentity identity = identity(providerRef);
        GenericKubernetesResource resource = get(identity.name());
        if (resource == null) {
            throw new SandboxProviderException(SandboxFailureCategory.PROVIDER_RESOURCE_LOST,
                    "TaskSandbox resource is absent");
        }
        verifyUid(resource, providerRef);
        return resource;
    }

    private GenericKubernetesResource get(String name) {
        try {
            return client.genericKubernetesResources(TASK_SANDBOX_CONTEXT).inNamespace(namespace)
                    .withName(name).get();
        } catch (KubernetesClientException error) {
            throw providerError(error, "reading TaskSandbox");
        }
    }

    private GenericKubernetesResource apply(GenericKubernetesResource resource) {
        try {
            if (resource.getMetadata() != null) {
                resource.getMetadata().setUid(null);
                resource.getMetadata().setResourceVersion(null);
                resource.getMetadata().setGeneration(null);
                resource.getMetadata().setManagedFields(null);
                resource.getMetadata().setCreationTimestamp(null);
            }
            return client.resource(resource).inNamespace(namespace).fieldManager(FIELD_MANAGER).serverSideApply();
        } catch (KubernetesClientException error) {
            throw providerError(error, "applying TaskSandbox");
        }
    }

    private void assertSameProvision(GenericKubernetesResource resource, SandboxProvisionCommand command) {
        Map<String, Object> spec = map(resource.get("spec"));
        boolean same = command.taskId().toString().equals(text(spec.get("taskId")))
                && command.attemptId().toString().equals(text(spec.get("attemptId")))
                && command.idempotencyKey().equals(text(spec.get("idempotencyKey")))
                && command.profile().name().equals(text(spec.get("profile")))
                && command.template().equals(text(spec.get("template")))
                && command.expiresAt().toString().equals(text(spec.get("expiresAt")))
                && properties.runtimeClassName(command.profile()).equals(text(spec.get("runtimeClassName")));
        if (!same) throw conflict("TaskSandbox spec conflicts with the existing resource");
    }

    private void verifyUid(GenericKubernetesResource resource, SandboxProviderRef providerRef) {
        String actual = resource.getMetadata() == null ? null : resource.getMetadata().getUid();
        if (!providerRef.resourceUid().equals(actual)) {
            throw new SandboxProviderException(SandboxFailureCategory.PROVIDER_RESOURCE_LOST,
                    "TaskSandbox resource UID does not match the provider reference");
        }
    }

    private void validateProvider(SandboxProviderRef providerRef) {
        if (!PROVIDER.equals(providerRef.provider())) {
            throw new SandboxProviderException(SandboxFailureCategory.PROVIDER_RESPONSE_INVALID,
                    "unsupported sandbox provider");
        }
    }

    private ResourceIdentity identity(SandboxProviderRef ref) {
        String prefix = namespace + "/";
        if (!ref.resourceId().startsWith(prefix) || ref.resourceId().indexOf('/', prefix.length()) >= 0) {
            throw new SandboxProviderException(SandboxFailureCategory.PROVIDER_RESPONSE_INVALID,
                    "sandbox provider resource ID is outside the configured namespace");
        }
        return new ResourceIdentity(ref.resourceId().substring(prefix.length()));
    }

    private static void setSpec(GenericKubernetesResource resource, Map<String, Object> spec) {
        resource.setAdditionalProperty("spec", new LinkedHashMap<>(spec));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private static SandboxProviderPhase phase(Object value) {
        String text = text(value);
        if (text == null) return SandboxProviderPhase.PROVISIONING;
        try {
            String normalized = text.toUpperCase(Locale.ROOT);
            return normalized.equals("PENDING") ? SandboxProviderPhase.PROVISIONING
                    : SandboxProviderPhase.valueOf(normalized);
        } catch (IllegalArgumentException error) {
            throw new SandboxProviderException(SandboxFailureCategory.PROVIDER_RESPONSE_INVALID,
                    "TaskSandbox status phase is invalid");
        }
    }

    private static SandboxFailure failure(Map<String, Object> status, SandboxProviderPhase phase) {
        if (phase != SandboxProviderPhase.FAILED) return null;
        String category = text(status.get("failureCategory"));
        SandboxFailureCategory value;
        try {
            value = category == null ? SandboxFailureCategory.PROVIDER_RESPONSE_INVALID
                    : SandboxFailureCategory.valueOf(category.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            value = SandboxFailureCategory.PROVIDER_RESPONSE_INVALID;
        }
        return new SandboxFailure(value, text(status.get("message")));
    }

    private static boolean conditionTrue(Map<String, Object> status, String directField, String... conditionTypes) {
        Object direct = status.get(directField);
        if (Boolean.TRUE.equals(direct) || "true".equalsIgnoreCase(text(direct))) return true;
        Object conditions = status.get("conditions");
        if (!(conditions instanceof Iterable<?> values)) return false;
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> condition)) continue;
            String type = text(condition.get("type"));
            String conditionStatus = text(condition.get("status"));
            if (type == null || conditionStatus == null || !"true".equalsIgnoreCase(conditionStatus)
                    && !"ready".equalsIgnoreCase(conditionStatus)
                    && !"healthy".equalsIgnoreCase(conditionStatus)) continue;
            for (String expectedType : conditionTypes) {
                if (expectedType.equalsIgnoreCase(type)) return true;
            }
        }
        return false;
    }

    private static long observedGeneration(GenericKubernetesResource resource) {
        Map<String, Object> status = map(resource.get("status"));
        return longValue(status.get("observedGeneration"),
                longValue(resource.getMetadata() == null ? null : resource.getMetadata().getGeneration(), 0));
    }

    private static long longValue(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static Instant instant(Object value, Instant fallback) {
        if (value == null) return fallback;
        try {
            return Instant.parse(String.valueOf(value));
        } catch (RuntimeException error) {
            throw new SandboxProviderException(SandboxFailureCategory.PROVIDER_RESPONSE_INVALID,
                    "TaskSandbox expiry is invalid");
        }
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static SandboxProviderException providerError(KubernetesClientException error, String operation) {
        SandboxFailureCategory category = error.getCode() == 404 ? SandboxFailureCategory.PROVIDER_RESOURCE_LOST
                : error.getCode() == 403 ? SandboxFailureCategory.POLICY_REJECTED
                : error.getCode() == 409 ? SandboxFailureCategory.IDEMPOTENCY_CONFLICT
                : error.getCode() == 429 ? SandboxFailureCategory.RESOURCE_QUOTA_EXCEEDED
                : SandboxFailureCategory.KUBERNETES_UNAVAILABLE;
        return new SandboxProviderException(category, operation + " failed: " + error.getCode());
    }

    private static SandboxProviderException conflict(String message) {
        return new SandboxProviderException(SandboxFailureCategory.IDEMPOTENCY_CONFLICT, message);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    private record ResourceIdentity(String name) { }
}
