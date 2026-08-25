package io.agentteams.operator;

import io.agentteams.application.api.SandboxProfile;
import io.fabric8.kubernetes.api.model.CapabilitiesBuilder;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.SecurityContextBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobSpecBuilder;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TaskSandboxResourceFactory {
    private final Map<SandboxProfile, String> runtimeClasses;

    public TaskSandboxResourceFactory(Map<SandboxProfile, String> runtimeClasses) {
        this.runtimeClasses = Map.copyOf(runtimeClasses);
        for (SandboxProfile profile : new SandboxProfile[]{SandboxProfile.ISOLATED, SandboxProfile.HARDENED}) {
            String runtimeClass = this.runtimeClasses.get(profile);
            if (runtimeClass == null || runtimeClass.isBlank()) {
                throw new IllegalArgumentException("runtime class is missing for " + profile);
            }
        }
    }

    public Job job(TaskSandbox sandbox) {
        String name = name(sandbox);
        TaskSandboxSpec spec = sandbox.getSpec();
        if (spec == null || spec.profile() == SandboxProfile.NONE) {
            throw new IllegalArgumentException("TaskSandbox profile must be ISOLATED or HARDENED");
        }
        Map<String, String> labels = labels(sandbox);
        Map<String, Quantity> requests = quantities(spec.resources());
        ContainerBuilder container = new ContainerBuilder()
                .withName("sandbox")
                .withImage(spec.image())
                .withImagePullPolicy("IfNotPresent")
                .withSecurityContext(new SecurityContextBuilder()
                        .withPrivileged(false)
                        .withAllowPrivilegeEscalation(false)
                        .withReadOnlyRootFilesystem(true)
                        .withRunAsNonRoot(true)
                        .withCapabilities(new CapabilitiesBuilder().withDrop("ALL").build())
                        .build())
                .withResources(new ResourceRequirementsBuilder().withRequests(requests).withLimits(requests).build());
        PodSpecBuilder podSpec = new PodSpecBuilder()
                .withAutomountServiceAccountToken(false)
                .withHostNetwork(false)
                .withHostPID(false)
                .withRestartPolicy("Never")
                .withRuntimeClassName(runtimeClasses.get(spec.profile()))
                .withContainers(container.build());
        return new JobBuilder()
                .withApiVersion("batch/v1")
                .withKind("Job")
                .withMetadata(metadata(sandbox, name, labels))
                .withSpec(new JobSpecBuilder()
                        .withBackoffLimit(0)
                        .withActiveDeadlineSeconds((long) spec.ttlSeconds())
                        .withTtlSecondsAfterFinished(spec.ttlSeconds())
                        .withTemplate(new PodTemplateSpecBuilder()
                                .withMetadata(new ObjectMetaBuilder().withLabels(labels).build())
                                .withSpec(podSpec.build()).build())
                        .build())
                .build();
    }

    private static Map<String, Quantity> quantities(Map<String, String> values) {
        Map<String, Quantity> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!key.equals("cpu") && !key.equals("memory")) {
                throw new IllegalArgumentException("only cpu and memory resources are supported");
            }
            result.put(key, new Quantity(value));
        });
        return result;
    }

    private static ObjectMeta metadata(TaskSandbox sandbox, String name, Map<String, String> labels) {
        ObjectMetaBuilder builder = new ObjectMetaBuilder().withName(name)
                .withNamespace(namespace(sandbox)).withLabels(labels);
        if (sandbox.getMetadata().getUid() != null && !sandbox.getMetadata().getUid().isBlank()) {
            builder.withOwnerReferences(new OwnerReferenceBuilder()
                    .withApiVersion("agentteams.io/v1alpha1").withKind("TaskSandbox")
                    .withName(sandbox.getMetadata().getName()).withUid(sandbox.getMetadata().getUid())
                    .withController(true).withBlockOwnerDeletion(true).build());
        }
        return builder.build();
    }

    private static Map<String, String> labels(TaskSandbox sandbox) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app.kubernetes.io/name", "agentteams-task-sandbox");
        labels.put("app.kubernetes.io/managed-by", "agentteams-operator");
        labels.put("agentteams.io/task-id", sandbox.getSpec().taskId());
        labels.put("agentteams.io/attempt-id", sandbox.getSpec().attemptId());
        labels.put("agentteams.io/sandbox-profile", sandbox.getSpec().profile().name());
        return labels;
    }

    private static String name(TaskSandbox sandbox) {
        if (sandbox.getMetadata() == null || sandbox.getMetadata().getName() == null
                || sandbox.getMetadata().getName().isBlank()) {
            throw new IllegalArgumentException("TaskSandbox metadata.name must not be blank");
        }
        return sandbox.getMetadata().getName() + "-job";
    }

    private static String namespace(TaskSandbox sandbox) {
        return sandbox.getMetadata().getNamespace() == null ? "default" : sandbox.getMetadata().getNamespace();
    }
}
