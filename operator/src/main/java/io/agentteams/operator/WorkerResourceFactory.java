package io.agentteams.operator;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpecBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorkerResourceFactory {
    private static final int GRPC_PORT = 9090;

    private WorkerResourceFactory() { }

    public static Deployment deployment(Worker worker) {
        String name = name(worker);
        Map<String, String> labels = labels(worker);
        WorkerSpec spec = worker.getSpec();
        ContainerBuilder container = new ContainerBuilder()
                .withName("worker")
                .withImage(spec.image())
                .withPorts(new io.fabric8.kubernetes.api.model.ContainerPortBuilder()
                        .withName("grpc").withContainerPort(GRPC_PORT).build())
                .withEnv(spec.env().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> new EnvVarBuilder().withName(entry.getKey()).withValue(entry.getValue()).build())
                        .toList());
        return new DeploymentBuilder()
                .withApiVersion("apps/v1")
                .withKind("Deployment")
                .withMetadata(metadata(worker, labels))
                .withSpec(new DeploymentSpecBuilder()
                        .withReplicas(spec.replicas())
                        .withSelector(new LabelSelectorBuilder().withMatchLabels(labels).build())
                        .withTemplate(new PodTemplateSpecBuilder()
                                .withMetadata(new ObjectMetaBuilder().withLabels(labels).build())
                                .withSpec(new PodSpecBuilder()
                                        .withAutomountServiceAccountToken(false)
                                        .withContainers(container.build()).build())
                                .build())
                        .build())
                .build();
    }

    public static Service service(Worker worker) {
        Map<String, String> labels = labels(worker);
        return new ServiceBuilder()
                .withApiVersion("v1")
                .withKind("Service")
                .withMetadata(metadata(worker, labels))
                .withSpec(new io.fabric8.kubernetes.api.model.ServiceSpecBuilder()
                        .withType("ClusterIP")
                        .withSelector(labels)
                        .withPorts(new ServicePortBuilder().withName("grpc").withPort(GRPC_PORT)
                                .withTargetPort(new io.fabric8.kubernetes.api.model.IntOrString(GRPC_PORT)).build())
                        .build())
                .build();
    }

    private static io.fabric8.kubernetes.api.model.ObjectMeta metadata(Worker worker, Map<String, String> labels) {
        ObjectMetaBuilder builder = new ObjectMetaBuilder()
                .withName(name(worker)).withNamespace(namespace(worker)).withLabels(labels);
        if (worker.getMetadata().getUid() != null && !worker.getMetadata().getUid().isBlank()) {
            builder.withOwnerReferences(new OwnerReferenceBuilder()
                    .withApiVersion("io.agentteams/v1alpha1").withKind("Worker")
                    .withName(name(worker)).withUid(worker.getMetadata().getUid())
                    .withController(true).withBlockOwnerDeletion(true).build());
        }
        return builder.build();
    }

    private static Map<String, String> labels(Worker worker) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app.kubernetes.io/name", "agentteams-worker");
        labels.put("app.kubernetes.io/managed-by", "agentteams-operator");
        labels.put("agentteams.io/agent-id", worker.getSpec().agentId());
        labels.put("agentteams.io/runtime", worker.getSpec().runtime());
        return labels;
    }

    private static String name(Worker worker) {
        if (worker.getMetadata() == null || worker.getMetadata().getName() == null
                || worker.getMetadata().getName().isBlank()) {
            throw new IllegalArgumentException("Worker metadata.name must not be blank");
        }
        return worker.getMetadata().getName();
    }

    private static String namespace(Worker worker) {
        return worker.getMetadata().getNamespace() == null ? "default" : worker.getMetadata().getNamespace();
    }
}
