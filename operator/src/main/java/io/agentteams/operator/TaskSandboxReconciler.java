package io.agentteams.operator;

import io.agentteams.application.api.SandboxProfile;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@ControllerConfiguration
public final class TaskSandboxReconciler implements Reconciler<TaskSandbox> {
    public static final String FINALIZER = "agentteams.io/task-sandbox-cleanup";
    private static final Duration RESCHEDULE_AFTER = Duration.ofSeconds(10);
    private static final ResourceDefinitionContext TASK_SANDBOX_CONTEXT = new ResourceDefinitionContext.Builder()
            .withGroup("agentteams.io").withVersion("v1alpha1").withKind("TaskSandbox")
            .withPlural("tasksandboxes").withNamespaced(true).build();

    private final KubernetesClient client;
    private final TaskSandboxResourceFactory resources;

    public TaskSandboxReconciler(KubernetesClient client) {
        this(client, new TaskSandboxResourceFactory(runtimeClasses()));
    }

    TaskSandboxReconciler(KubernetesClient client, TaskSandboxResourceFactory resources) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.resources = java.util.Objects.requireNonNull(resources, "resources");
    }

    @Override
    public UpdateControl<TaskSandbox> reconcile(TaskSandbox resource, Context<TaskSandbox> context) {
        Objects.requireNonNull(resource, "resource");
        String namespace = resource.getMetadata().getNamespace() == null
                ? "default" : resource.getMetadata().getNamespace();
        Job desiredJob = resources.job(resource);
        Service desiredService = resources.service(resource);
        boolean finalizerChanged = false;
        if (!resource.isMarkedForDeletion() && !resource.hasFinalizer(FINALIZER)) {
            finalizerChanged = addFinalizer(resource);
        }

        Job observedJob = getJob(namespace, desiredJob.getMetadata().getName());
        Service observedService = getService(namespace, desiredService.getMetadata().getName());
        if (resource.isMarkedForDeletion() || terminationRequested(namespace, resource.getMetadata().getName())) {
            return reconcileTermination(resource, namespace, observedJob, observedService, finalizerChanged);
        }

        if ((observedJob != null && !isControlled(observedJob, resource))
                || (observedService != null && !isControlled(observedService, resource))) {
            TaskSandboxStatus status = new TaskSandboxStatus();
            status.setPhase("FAILED");
            status.setFailureCategory("POLICY_REJECTED");
            status.setObservedGeneration(resource.getMetadata().getGeneration());
            status.setMessage("Sandbox child is not controlled by this TaskSandbox");
            return updateStatus(resource, status, finalizerChanged);
        }

        boolean currentJob = isCurrentGeneration(observedJob, resource);
        if (observedService != null && !isCurrentGeneration(observedService, resource)) {
            if (isControlled(observedService, resource)) {
                client.services().inNamespace(namespace).withName(observedService.getMetadata().getName()).delete();
            }
            observedService = getService(namespace, desiredService.getMetadata().getName());
        }
        boolean previouslyReady = previouslyReady(resource);
        if (observedJob != null && !currentJob) {
            if (isControlled(observedJob, resource)) {
                client.batch().jobs().inNamespace(namespace).withName(observedJob.getMetadata().getName()).delete();
            }
            observedJob = getJob(namespace, desiredJob.getMetadata().getName());
            if (previouslyReady) {
                if (isControlled(observedService, resource)) {
                    client.services().inNamespace(namespace).withName(observedService.getMetadata().getName()).delete();
                }
                return updateStatus(resource, lostStatus(resource), finalizerChanged);
            }
        }

        if (observedJob == null && previouslyReady) {
            if (isControlled(observedService, resource)) {
                client.services().inNamespace(namespace).withName(observedService.getMetadata().getName()).delete();
            }
            return updateStatus(resource, lostStatus(resource), finalizerChanged);
        }
        if (observedService == null) {
            client.services().inNamespace(namespace).resource(desiredService).createOrReplace();
            observedService = getService(namespace, desiredService.getMetadata().getName());
        }
        if (observedJob == null) {
            client.batch().jobs().inNamespace(namespace).resource(desiredJob).createOrReplace();
            observedJob = getJob(namespace, desiredJob.getMetadata().getName());
        }

        Endpoints endpoints = client.endpoints().inNamespace(namespace)
                .withName(desiredService.getMetadata().getName()).get();
        boolean runnerHealthy = observedJob != null && observedJob.getStatus() != null
                && observedJob.getStatus().getReady() != null && observedJob.getStatus().getReady() > 0;
        resource.setStatus(TaskSandboxStatusMapper.map(resource, observedJob, observedService,
                endpoints, runnerHealthy));
        return finalizerChanged
                ? UpdateControl.updateResourceAndStatus(resource).rescheduleAfter(RESCHEDULE_AFTER)
                : UpdateControl.updateStatus(resource).rescheduleAfter(RESCHEDULE_AFTER);
    }

    private UpdateControl<TaskSandbox> reconcileTermination(TaskSandbox resource, String namespace,
            Job observedJob, Service observedService, boolean finalizerChanged) {
        if (isControlled(observedJob, resource)) {
            client.batch().jobs().inNamespace(namespace).withName(observedJob.getMetadata().getName()).delete();
        }
        if (isControlled(observedService, resource)) {
            client.services().inNamespace(namespace).withName(observedService.getMetadata().getName()).delete();
        }
        Job remainingJob = getJob(namespace, resource.getMetadata().getName() + "-job");
        Service remainingService = getService(namespace, resource.getMetadata().getName());
        if (remainingJob == null && remainingService == null) {
            TaskSandboxStatus status = new TaskSandboxStatus();
            status.setPhase("DESTROYED");
            status.setObservedGeneration(resource.getMetadata().getGeneration());
            status.setMessage("Sandbox children destroyed");
            resource.setStatus(status);
            if (resource.hasFinalizer(FINALIZER)) {
                finalizerChanged = removeFinalizer(resource) || finalizerChanged;
            }
        } else {
            TaskSandboxStatus status = new TaskSandboxStatus();
            status.setPhase("STOPPING");
            status.setObservedGeneration(resource.getMetadata().getGeneration());
            status.setMessage("Waiting for Sandbox children to disappear");
            resource.setStatus(status);
        }
        return finalizerChanged
                ? UpdateControl.updateResourceAndStatus(resource).rescheduleAfter(RESCHEDULE_AFTER)
                : UpdateControl.updateStatus(resource).rescheduleAfter(RESCHEDULE_AFTER);
    }

    private TaskSandboxStatus lostStatus(TaskSandbox resource) {
        TaskSandboxStatus status = TaskSandboxStatusMapper.map(resource, null, null, null, false);
        status.setPhase("LOST");
        status.setMessage("Sandbox workload disappeared after becoming ready");
        return status;
    }

    private UpdateControl<TaskSandbox> updateStatus(TaskSandbox resource, TaskSandboxStatus status,
            boolean finalizerChanged) {
        resource.setStatus(status);
        return finalizerChanged
                ? UpdateControl.updateResourceAndStatus(resource).rescheduleAfter(RESCHEDULE_AFTER)
                : UpdateControl.updateStatus(resource).rescheduleAfter(RESCHEDULE_AFTER);
    }

    private Job getJob(String namespace, String name) {
        return client.batch().jobs().inNamespace(namespace).withName(name).get();
    }

    private Service getService(String namespace, String name) {
        return client.services().inNamespace(namespace).withName(name).get();
    }

    private boolean terminationRequested(String namespace, String name) {
        GenericKubernetesResource raw = client.genericKubernetesResources(TASK_SANDBOX_CONTEXT)
                .inNamespace(namespace).withName(name).get();
        if (raw == null || !(raw.get("spec") instanceof Map<?, ?> spec)) return false;
        return Boolean.TRUE.equals(spec.get("terminationRequested"));
    }

    private static boolean previouslyReady(TaskSandbox resource) {
        TaskSandboxStatus status = resource.getStatus();
        Long generation = resource.getMetadata().getGeneration();
        return status != null && "READY".equals(status.getPhase())
                && (generation == null || generation.equals(status.getObservedGeneration()));
    }

    private static boolean isCurrentGeneration(HasMetadata child, TaskSandbox resource) {
        if (child == null) return true;
        Map<String, String> labels = child.getMetadata() == null ? null : child.getMetadata().getLabels();
        String generation = labels == null ? null : labels.get(TaskSandboxResourceFactory.GENERATION_LABEL);
        Long current = resource.getMetadata().getGeneration();
        return generation != null && current != null && String.valueOf(current).equals(generation);
    }

    private static boolean isControlled(HasMetadata child, TaskSandbox resource) {
        if (child == null || child.getMetadata() == null) return false;
        Map<String, String> labels = child.getMetadata().getLabels();
        if (labels == null || !"agentteams-operator".equals(labels.get("app.kubernetes.io/managed-by"))
                || !resource.getSpec().taskId().equals(labels.get("agentteams.io/task-id"))
                || !resource.getSpec().attemptId().equals(labels.get("agentteams.io/attempt-id"))) {
            return false;
        }
        if (resource.getMetadata().getUid() == null || child.getMetadata().getOwnerReferences() == null
                || child.getMetadata().getOwnerReferences().isEmpty()) return false;
        return child.getMetadata().getOwnerReferences().stream()
                .anyMatch(owner -> resource.getMetadata().getUid().equals(owner.getUid())
                        && "TaskSandbox".equals(owner.getKind()));
    }

    private static boolean addFinalizer(TaskSandbox resource) {
        List<String> finalizers = new ArrayList<>(resource.getFinalizers());
        if (finalizers.contains(FINALIZER)) return false;
        finalizers.add(FINALIZER);
        resource.getMetadata().setFinalizers(finalizers);
        return true;
    }

    private static boolean removeFinalizer(TaskSandbox resource) {
        List<String> finalizers = new ArrayList<>(resource.getFinalizers());
        if (!finalizers.remove(FINALIZER)) return false;
        resource.getMetadata().setFinalizers(finalizers);
        return true;
    }

    static Map<SandboxProfile, String> runtimeClasses() {
        return Map.of(
                SandboxProfile.ISOLATED, textOrDefault(System.getenv("AGENTTEAMS_SANDBOX_RUNTIMECLASS_ISOLATED"),
                        "gvisor"),
                SandboxProfile.HARDENED, textOrDefault(System.getenv("AGENTTEAMS_SANDBOX_RUNTIMECLASS_HARDENED"),
                        "kata-qemu"));
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
