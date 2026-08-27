package io.agentteams.operator;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapEnvSourceBuilder;
import io.fabric8.kubernetes.api.model.EnvFromSourceBuilder;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.SecretVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpecBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.TCPSocketActionBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorkerResourceFactory {
    private static final int GRPC_PORT = 9090;
    private static final String SECRET_RELOADER_ANNOTATION = "secret.reloader.stakater.com/reload";
    private static final String RUNTIME_CONFIG_MAP_ENV = "AGENTTEAMS_RUNTIME_CONFIG_MAP";
    private static final String RUNTIME_CONFIG_MAP_ANNOTATION = "agentteams.io/runtime-config-map";
    static final String SPEC_DIGEST_ANNOTATION = "agentteams.io/spec-digest";
    static final String RUNTIME_ANNOTATION = "agentteams.io/runtime";
    static final String CONFIG_REVISION_ANNOTATION = "agentteams.io/config-revision";
    static final String SECRET_GENERATION_ANNOTATION = "agentteams.io/secret-generation";

    private WorkerResourceFactory() { }

    public static Deployment deployment(Worker worker) {
        String name = name(worker);
        Map<String, String> labels = labels(worker);
        WorkerSpec spec = worker.getSpec();
        Map<String, String> environment = new LinkedHashMap<>(spec.env());
        String runtimeConfigMap = runtimeConfigMap(worker, spec);
        environment.remove(RUNTIME_CONFIG_MAP_ENV);
        // The CR identity is canonical. A stale or conflicting value supplied
        // through env must not make the worker register as another Agent.
        environment.put("AGENTTEAMS_AGENT_ID", spec.agentId());
        environment.put("AGENTTEAMS_RUNTIME", spec.runtime());
        ContainerBuilder container = new ContainerBuilder()
                .withName("worker")
                .withImage(spec.image())
                .withImagePullPolicy("IfNotPresent")
                .withEnvFrom(new EnvFromSourceBuilder()
                        .withConfigMapRef(new ConfigMapEnvSourceBuilder()
                                .withName(runtimeConfigMap).build()).build())
                .withPorts(new io.fabric8.kubernetes.api.model.ContainerPortBuilder()
                        .withName("grpc").withContainerPort(GRPC_PORT).build())
                .withReadinessProbe(new ProbeBuilder().withTcpSocket(new TCPSocketActionBuilder()
                        .withPort(new io.fabric8.kubernetes.api.model.IntOrString(GRPC_PORT)).build())
                        .withInitialDelaySeconds(5).withPeriodSeconds(10).withFailureThreshold(3).build())
                .withLivenessProbe(new ProbeBuilder().withTcpSocket(new TCPSocketActionBuilder()
                        .withPort(new io.fabric8.kubernetes.api.model.IntOrString(GRPC_PORT)).build())
                        .withInitialDelaySeconds(15).withPeriodSeconds(20).withFailureThreshold(3).build())
                .withEnv(environment.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> new EnvVarBuilder().withName(entry.getKey()).withValue(entry.getValue()).build())
                        .toList());
        PodSpecBuilder podSpec = new PodSpecBuilder()
                .withAutomountServiceAccountToken(false)
                .withContainers(container.build());
        if (!spec.tlsSecret().isBlank()) {
            container.withVolumeMounts(new VolumeMountBuilder()
                    .withName("agentteams-gateway-tls")
                    .withMountPath("/etc/agentteams/gateway-tls")
                    .withReadOnly(true).build());
            podSpec.withContainers(container.build()).withVolumes(new VolumeBuilder()
                    .withName("agentteams-gateway-tls")
                    .withSecret(new SecretVolumeSourceBuilder().withSecretName(spec.tlsSecret()).build())
                    .build());
        }
        Map<String, String> deploymentAnnotations = new LinkedHashMap<>();
        if (!spec.tlsSecret().isBlank()) {
            // Reloader is optional. When installed, this stable annotation
            // turns an external Secret update into a rolling restart so the
            // gRPC TLS context cannot keep serving the old certificate.
            deploymentAnnotations.put(SECRET_RELOADER_ANNOTATION, spec.tlsSecret());
        }
        return new DeploymentBuilder()
                .withApiVersion("apps/v1")
                .withKind("Deployment")
                .withMetadata(metadata(worker, labels, deploymentAnnotations))
                .withSpec(new DeploymentSpecBuilder()
                        .withReplicas(spec.replicas())
                        .withSelector(new LabelSelectorBuilder().withMatchLabels(labels).build())
                        .withTemplate(new PodTemplateSpecBuilder()
                                .withMetadata(new ObjectMetaBuilder().withLabels(labels)
                                        .withAnnotations(versionAnnotations(spec)).build())
                                .withSpec(podSpec.build())
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
        return metadata(worker, labels, Map.of());
    }

    private static io.fabric8.kubernetes.api.model.ObjectMeta metadata(
            Worker worker, Map<String, String> labels, Map<String, String> annotations) {
        ObjectMetaBuilder builder = new ObjectMetaBuilder()
                .withName(name(worker)).withNamespace(namespace(worker)).withLabels(labels);
        if (!annotations.isEmpty()) {
            builder.withAnnotations(annotations);
        }
        if (worker.getMetadata().getUid() != null && !worker.getMetadata().getUid().isBlank()) {
            builder.withOwnerReferences(new OwnerReferenceBuilder()
                    .withApiVersion("agentteams.io/v1alpha1").withKind("Worker")
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

    private static Map<String, String> versionAnnotations(WorkerSpec spec) {
        Map<String, String> annotations = new LinkedHashMap<>();
        putIfPresent(annotations, SPEC_DIGEST_ANNOTATION, spec.specDigest());
        putIfPresent(annotations, RUNTIME_ANNOTATION, spec.runtime());
        putIfPresent(annotations, CONFIG_REVISION_ANNOTATION, spec.configRevision());
        putIfPresent(annotations, SECRET_GENERATION_ANNOTATION, spec.secretGeneration());
        return annotations;
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
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

    private static String runtimeConfigMap(Worker worker, WorkerSpec spec) {
        Map<String, String> annotations = worker.getMetadata().getAnnotations();
        String configured = annotations == null ? null : annotations.get(RUNTIME_CONFIG_MAP_ANNOTATION);
        if (configured == null || configured.isBlank()) {
            configured = spec.env().get(RUNTIME_CONFIG_MAP_ENV);
        }
        if (configured == null || configured.isBlank()) {
            Map<String, String> labels = worker.getMetadata().getLabels();
            String release = labels == null ? null : labels.get("app.kubernetes.io/instance");
            if (release != null && !release.isBlank()) {
                configured = release.trim() + "-agentteams-java-agent-runtime";
            }
        }
        if (configured == null || configured.isBlank()) {
            // Existing Worker CRs predate explicit release binding. This is the
            // stable name rendered by the current Helm runtime ConfigMap.
            configured = "agentteams-java-agent-runtime";
        }
        String name = configured.trim();
        if (!name.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")) {
            throw new IllegalArgumentException("Worker runtime ConfigMap name is invalid");
        }
        return name;
    }
}
