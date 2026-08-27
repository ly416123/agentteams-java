package io.agentteams.operator;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ControllerConfiguration
public final class WorkerReconciler implements Reconciler<Worker> {
    private final KubernetesClient client;
    private final WorkerOperationObservationReporter observations;
    private final WorkerOperationRecovery recovery;
    private final ObjectMapper objectMapper;

    public WorkerReconciler(KubernetesClient client) {
        this(client, WorkerOperationObservationReporter.noop(), WorkerOperationRecovery.noop(), new ObjectMapper());
    }

    public WorkerReconciler(KubernetesClient client, WorkerOperationObservationReporter observations) {
        this(client, observations, WorkerOperationRecovery.noop(), new ObjectMapper());
    }

    public WorkerReconciler(KubernetesClient client, WorkerOperationObservationReporter observations,
            WorkerOperationRecovery recovery, ObjectMapper objectMapper) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.observations = java.util.Objects.requireNonNull(observations, "observations");
        this.recovery = java.util.Objects.requireNonNull(recovery, "recovery");
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public UpdateControl<Worker> reconcile(Worker resource, Context<Worker> context) {
        String namespace = resource.getMetadata().getNamespace() == null
                ? "default" : resource.getMetadata().getNamespace();
        String name = resource.getMetadata().getName();
        restoreFailedRollout(resource, namespace, name);
        client.apps().deployments().inNamespace(namespace)
                .resource(WorkerResourceFactory.deployment(resource)).createOrReplace();
        client.services().inNamespace(namespace)
                .resource(WorkerResourceFactory.service(resource)).createOrReplace();

        Deployment deployment = client.apps().deployments().inNamespace(namespace).withName(name).get();
        WorkerStatus status = statusFor(resource, deployment);
        resource.setStatus(status);
        observations.report(resource, status, Instant.now());
        UpdateControl<Worker> update = UpdateControl.updateStatus(resource);
        // A deleted or externally mutated Deployment does not necessarily
        // enqueue its Worker owner. Keep a bounded repair loop so the CR
        // cannot remain Ready while its child Deployment is missing.
        return update.rescheduleAfter(Duration.ofSeconds(30));
    }

    private void restoreFailedRollout(Worker resource, String namespace, String name) {
        UUID agentId;
        try {
            agentId = UUID.fromString(resource.getSpec().agentId());
        } catch (IllegalArgumentException error) {
            return;
        }
        Optional<WorkerOperationRecovery.FailedWorkerOperation> failed = recovery.failed(agentId);
        if (failed.isEmpty() || !agentId.equals(failed.get().agentId())) {
            return;
        }
        WorkerSpec stable;
        try {
            stable = WorkerStableSpec.parse(failed.get().previousStableSpec(), resource.getSpec().agentId(), objectMapper);
        } catch (IllegalArgumentException error) {
            // A malformed snapshot is intentionally left for manual recovery;
            // never replace a live Worker with a partially decoded spec.
            return;
        }
        resource.setSpec(stable);
        client.resources(Worker.class).inNamespace(namespace).withName(name).replace(resource);
        recovery.rollback(failed.get().id(), failed.get().version());
    }

    static WorkerStatus statusFor(Worker resource, Deployment deployment) {
        int ready = deployment == null || deployment.getStatus() == null
                || deployment.getStatus().getReadyReplicas() == null
                ? 0 : deployment.getStatus().getReadyReplicas();
        boolean readyForDesiredReplicas = ready >= resource.getSpec().replicas();
        WorkerStatus status = new WorkerStatus();
        status.setReadyReplicas(ready);
        status.setObservedGeneration(resource.getMetadata().getGeneration());
        WorkerStatus previous = resource.getStatus();
        if (previous != null) {
            status.setObservedSpecDigest(previous.getObservedSpecDigest());
            status.setObservedRuntime(previous.getObservedRuntime());
            status.setObservedConfigRevision(previous.getObservedConfigRevision());
            status.setObservedSecretGeneration(previous.getObservedSecretGeneration());
        }
        Map<String, String> rawAnnotations = deployment == null
                || deployment.getSpec() == null
                || deployment.getSpec().getTemplate() == null
                || deployment.getSpec().getTemplate().getMetadata() == null
                ? Map.of() : deployment.getSpec().getTemplate().getMetadata().getAnnotations();
        Map<String, String> annotations = rawAnnotations == null ? Map.of() : rawAnnotations;
        boolean versionConfirmed = versionConfirmed(resource.getSpec(), annotations);
        boolean rolloutComplete = rolloutComplete(resource, deployment);
        boolean deploymentReady = readyForDesiredReplicas && versionConfirmed && rolloutComplete;
        status.setPhase(deploymentReady ? "Ready" : "Progressing");
        status.setMessage(deploymentReady
                ? "Worker deployment is ready" : "Waiting for Worker deployment readiness");
        if (rolloutComplete) {
            status.setObservedSpecDigest(annotations.get(WorkerResourceFactory.SPEC_DIGEST_ANNOTATION));
            status.setObservedRuntime(annotations.get(WorkerResourceFactory.RUNTIME_ANNOTATION));
            status.setObservedConfigRevision(annotations.get(WorkerResourceFactory.CONFIG_REVISION_ANNOTATION));
            status.setObservedSecretGeneration(annotations.get(WorkerResourceFactory.SECRET_GENERATION_ANNOTATION));
        }
        return status;
    }

    private static boolean rolloutComplete(Worker resource, Deployment deployment) {
        if (!hasVersionExpectation(resource.getSpec())) {
            return true;
        }
        DeploymentStatus deploymentStatus = deployment == null ? null : deployment.getStatus();
        if (deploymentStatus == null || deploymentStatus.getObservedGeneration() == null
                || deploymentStatus.getUpdatedReplicas() == null
                || deploymentStatus.getAvailableReplicas() == null) {
            return false;
        }
        Long generation = resource.getMetadata().getGeneration();
        return (generation == null || deploymentStatus.getObservedGeneration() >= generation)
                && deploymentStatus.getUpdatedReplicas() >= resource.getSpec().replicas()
                && deploymentStatus.getAvailableReplicas() >= resource.getSpec().replicas();
    }

    private static boolean hasVersionExpectation(WorkerSpec spec) {
        return !spec.specDigest().isBlank() || !spec.configRevision().isBlank()
                || !spec.secretGeneration().isBlank();
    }

    private static boolean versionConfirmed(WorkerSpec spec, Map<String, String> annotations) {
        if (spec.specDigest().isBlank() && spec.configRevision().isBlank()
                && spec.secretGeneration().isBlank()) {
            return true;
        }
        return same(spec.specDigest(), annotations.get(WorkerResourceFactory.SPEC_DIGEST_ANNOTATION))
                && same(spec.runtime(), annotations.get(WorkerResourceFactory.RUNTIME_ANNOTATION))
                && same(spec.configRevision(), annotations.get(WorkerResourceFactory.CONFIG_REVISION_ANNOTATION))
                && same(spec.secretGeneration(), annotations.get(WorkerResourceFactory.SECRET_GENERATION_ANNOTATION));
    }

    private static boolean same(String expected, String observed) {
        return expected == null || expected.isBlank() ? observed == null || observed.isBlank()
                : expected.equals(observed);
    }
}
