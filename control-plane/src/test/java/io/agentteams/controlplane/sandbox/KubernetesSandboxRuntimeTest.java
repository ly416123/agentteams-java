package io.agentteams.controlplane.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import io.agentteams.application.api.SandboxFailureCategory;
import io.agentteams.application.api.SandboxObservation;
import io.agentteams.application.api.SandboxProfile;
import io.agentteams.application.api.SandboxProvisionCommand;
import io.agentteams.application.api.SandboxProviderException;
import io.agentteams.application.api.SandboxProviderPhase;
import io.agentteams.application.api.SandboxProviderRef;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NamespaceableResource;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"unchecked", "rawtypes"})
class KubernetesSandboxRuntimeTest {

    private static final UUID TASK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ATTEMPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-08-26T08:00:00Z");

    @Test
    void conflictingProvisionDoesNotReplaceExistingCustomResource() {
        KubernetesClient client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        Resource<GenericKubernetesResource> handle = mock(Resource.class);
        NonNamespaceOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>> namespaced = mock(NonNamespaceOperation.class);
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>> operation = mock(MixedOperation.class);
        GenericKubernetesResource existing = resource("cr-uid", Map.of(
                "taskId", TASK_ID.toString(),
                "attemptId", ATTEMPT_ID.toString(),
                "idempotencyKey", "sandbox:" + ATTEMPT_ID,
                "profile", "ISOLATED",
                "template", "template-a",
                "expiresAt", NOW.plusSeconds(300).toString()));
        when(client.genericKubernetesResources(any(ResourceDefinitionContext.class))).thenReturn(operation);
        when(operation.inNamespace("agentteams")).thenReturn(namespaced);
        when(namespaced.withName("task-sandbox-22222222222222222222222222222222")).thenReturn(handle);
        when(handle.get()).thenReturn(existing);

        KubernetesSandboxRuntime runtime = new KubernetesSandboxRuntime(client, "agentteams",
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

        runtime.ensureProvisioned(command("template-a"));

        assertThatThrownBy(() -> runtime.ensureProvisioned(command("template-b")))
                .isInstanceOf(SandboxProviderException.class)
                .extracting(error -> ((SandboxProviderException) error).category())
                .isEqualTo(SandboxFailureCategory.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void inspectReadsCrUidGenerationAndReadyEndpoint() {
        KubernetesClient client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        Resource<GenericKubernetesResource> handle = mock(Resource.class);
        NonNamespaceOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>> namespaced = mock(NonNamespaceOperation.class);
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>> operation = mock(MixedOperation.class);
        GenericKubernetesResource resource = resource("cr-uid", Map.of(
                "taskId", TASK_ID.toString(),
                "attemptId", ATTEMPT_ID.toString(),
                "idempotencyKey", "sandbox:" + ATTEMPT_ID,
                "profile", "ISOLATED",
                "template", "template-a",
                "expiresAt", NOW.plusSeconds(300).toString()));
        resource.setAdditionalProperties(new LinkedHashMap<>(Map.of(
                "spec", resource.get("spec"),
                "status", Map.of(
                "phase", "READY",
                "endpointRef", "sandbox+grpc://task-sandbox.agentteams.svc:7443/runner",
                "observedGeneration", 3L,
                "workloadUid", "workload-uid",
                "runnerReady", true,
                "healthy", true))));
        when(client.genericKubernetesResources(any(ResourceDefinitionContext.class))).thenReturn(operation);
        when(operation.inNamespace("agentteams")).thenReturn(namespaced);
        when(namespaced.withName("task-sandbox-22222222222222222222222222222222")).thenReturn(handle);
        when(handle.get()).thenReturn(resource);

        SandboxObservation observation = new KubernetesSandboxRuntime(client, "agentteams",
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC))
                .inspect(new SandboxProviderRef("kubernetes", "agentteams/task-sandbox-22222222222222222222222222222222",
                        "cr-uid"));

        assertThat(observation.phase()).isEqualTo(SandboxProviderPhase.READY);
        assertThat(observation.providerRef().resourceUid()).isEqualTo("cr-uid");
        assertThat(observation.observedGeneration()).isEqualTo(3L);
        assertThat(observation.endpointRef()).startsWith("sandbox+grpc://");
        assertThat(observation.workloadUid()).isEqualTo("workload-uid");
    }

    @Test
    void createRaceReReadsTheWinnerBeforeReturningItsReceipt() {
        KubernetesClient client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        Resource<GenericKubernetesResource> getHandle = mock(Resource.class);
        NamespaceableResource<GenericKubernetesResource> applyHandle = mock(NamespaceableResource.class);
        NonNamespaceOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>> namespaced = mock(NonNamespaceOperation.class);
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>> operation = mock(MixedOperation.class);
        GenericKubernetesResource applied = resource("winner-uid", Map.of());
        when(client.genericKubernetesResources(any(ResourceDefinitionContext.class))).thenReturn(operation);
        when(operation.inNamespace("agentteams")).thenReturn(namespaced);
        when(namespaced.withName("task-sandbox-22222222222222222222222222222222")).thenReturn(getHandle);
        when(getHandle.get()).thenReturn(null, applied);
        when(client.resource(any(GenericKubernetesResource.class))).thenReturn(applyHandle);
        when(applyHandle.inNamespace("agentteams")).thenReturn(applyHandle);
        when(applyHandle.fieldManager("agentteams-control-plane")).thenReturn(applyHandle);
        when(applyHandle.serverSideApply()).thenReturn(applied);

        var receipt = new KubernetesSandboxRuntime(client, "agentteams",
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC)).ensureProvisioned(command("template-a"));

        assertThat(receipt.providerRef().resourceUid()).isEqualTo("winner-uid");
        verify(getHandle, times(2)).get();
    }

    @Test
    void runtimeClassNameIsPartOfTheImmutableProvisionSpec() {
        KubernetesClient client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        Resource<GenericKubernetesResource> handle = mock(Resource.class);
        NonNamespaceOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>> namespaced = mock(NonNamespaceOperation.class);
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>> operation = mock(MixedOperation.class);
        GenericKubernetesResource existing = resource("cr-uid", Map.of("runtimeClassName", "other-runtime"));
        when(client.genericKubernetesResources(any(ResourceDefinitionContext.class))).thenReturn(operation);
        when(operation.inNamespace("agentteams")).thenReturn(namespaced);
        when(namespaced.withName("task-sandbox-22222222222222222222222222222222")).thenReturn(handle);
        when(handle.get()).thenReturn(existing);

        assertThatThrownBy(() -> new KubernetesSandboxRuntime(client, "agentteams",
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC)).ensureProvisioned(command("template-a")))
                .isInstanceOf(SandboxProviderException.class)
                .extracting(error -> ((SandboxProviderException) error).category())
                .isEqualTo(SandboxFailureCategory.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void readyWithoutRunnerHealthIsNotPublishedAsReady() {
        KubernetesClient client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        Resource<GenericKubernetesResource> handle = mock(Resource.class);
        NonNamespaceOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>> namespaced = mock(NonNamespaceOperation.class);
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>> operation = mock(MixedOperation.class);
        GenericKubernetesResource resource = resource("cr-uid", Map.of());
        resource.setAdditionalProperties(new LinkedHashMap<>(Map.of(
                "spec", resource.get("spec"),
                "status", Map.of(
                        "phase", "READY",
                        "endpointRef", "sandbox+grpc://task-sandbox.agentteams.svc:7443/runner",
                        "observedGeneration", 3L,
                        "workloadUid", "workload-uid",
                        "runnerReady", true))));
        when(client.genericKubernetesResources(any(ResourceDefinitionContext.class))).thenReturn(operation);
        when(operation.inNamespace("agentteams")).thenReturn(namespaced);
        when(namespaced.withName("task-sandbox-22222222222222222222222222222222")).thenReturn(handle);
        when(handle.get()).thenReturn(resource);

        SandboxObservation observation = new KubernetesSandboxRuntime(client, "agentteams",
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC))
                .inspect(new SandboxProviderRef("kubernetes", "agentteams/task-sandbox-22222222222222222222222222222222",
                        "cr-uid"));

        assertThat(observation.phase()).isEqualTo(SandboxProviderPhase.PROVISIONING);
    }

    private static SandboxProvisionCommand command(String template) {
        return new SandboxProvisionCommand(TASK_ID, ATTEMPT_ID, SandboxProfile.ISOLATED,
                Duration.ofMinutes(5), template, NOW, "sandbox:" + ATTEMPT_ID);
    }

    private static GenericKubernetesResource resource(String uid, Map<String, Object> spec) {
        GenericKubernetesResource resource = new GenericKubernetesResource();
        resource.setApiVersion("agentteams.io/v1alpha1");
        resource.setKind("TaskSandbox");
        resource.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
                .withName("task-sandbox-22222222222222222222222222222222")
                .withNamespace("agentteams").withUid(uid).withGeneration(3L).build());
        Map<String, Object> completeSpec = new LinkedHashMap<>(spec);
        completeSpec.putIfAbsent("taskId", TASK_ID.toString());
        completeSpec.putIfAbsent("attemptId", ATTEMPT_ID.toString());
        completeSpec.putIfAbsent("idempotencyKey", "sandbox:" + ATTEMPT_ID);
        completeSpec.putIfAbsent("profile", "ISOLATED");
        completeSpec.putIfAbsent("template", "template-a");
        completeSpec.putIfAbsent("expiresAt", NOW.plusSeconds(300).toString());
        completeSpec.putIfAbsent("runtimeClassName", "agentteams-sandbox");
        resource.setAdditionalProperties(new LinkedHashMap<>(Map.of("spec", completeSpec)));
        return resource;
    }
}
