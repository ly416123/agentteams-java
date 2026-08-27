package io.agentteams.operator;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.Service;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerResourceFactoryTest {

    @Test
    void mapsWorkerSpecToStableDeploymentAndService() {
        Worker worker = new Worker();
        worker.setMetadata(new ObjectMetaBuilder().withName("worker-a").withNamespace("agentteams")
                .withGeneration(4L).withUid("worker-uid").build());
        worker.setSpec(new WorkerSpec("agent-a", "qwenpaw", "example/worker:v1", 2,
                Map.of("MODEL", "deepseek", "AGENTTEAMS_RUNTIME_CONFIG_MAP",
                        "release-agentteams-java-agent-runtime")));

        Deployment deployment = WorkerResourceFactory.deployment(worker);
        Service service = WorkerResourceFactory.service(worker);

        assertThat(deployment.getMetadata().getName()).isEqualTo("worker-a");
        assertThat(deployment.getMetadata().getNamespace()).isEqualTo("agentteams");
        assertThat(deployment.getSpec().getReplicas()).isEqualTo(2);
        assertThat(deployment.getSpec().getTemplate().getSpec().getAutomountServiceAccountToken()).isFalse();
        assertThat(deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getImage())
                .isEqualTo("example/worker:v1");
        assertThat(deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv())
                .anySatisfy(env -> assertThat(env.getName()).isEqualTo("AGENTTEAMS_AGENT_ID"))
                .anySatisfy(env -> assertThat(env.getName()).isEqualTo("MODEL"));
        assertThat(deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().stream()
                .filter(env -> "AGENTTEAMS_AGENT_ID".equals(env.getName())).findFirst().orElseThrow().getValue())
                .isEqualTo("agent-a");
        assertThat(deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getImagePullPolicy())
                .isEqualTo("IfNotPresent");
        assertThat(deployment.getSpec().getTemplate().getMetadata().getLabels())
                .containsEntry("agentteams.io/agent-id", "agent-a")
                .containsEntry("agentteams.io/runtime", "qwenpaw");
        assertThat(deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe())
                .isNotNull();
        assertThat(deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getLivenessProbe())
                .isNotNull();
        assertThat(deployment.getMetadata().getOwnerReferences()).singleElement()
                .satisfies(owner -> assertThat(owner.getApiVersion()).isEqualTo("agentteams.io/v1alpha1"));
        assertThat(service.getMetadata().getName()).isEqualTo("worker-a");
        assertThat(service.getSpec().getPorts().get(0).getPort()).isEqualTo(9090);
        assertThat(service.getSpec().getSelector()).containsEntry("app.kubernetes.io/name", "agentteams-worker");
    }

    @Test
    void customResourceStartsWithNonNullSpecForFabric8Deserialization() {
        assertThat(new Worker().getSpec()).isNotNull();
    }

    @Test
    void writesCanonicalRuntimeAndOverridesConflictingUserEnvironment() {
        Worker worker = new Worker();
        worker.setMetadata(new ObjectMetaBuilder().withName("worker-runtime").withNamespace("agentteams").build());
        worker.setSpec(new WorkerSpec("agent-a", "AGENTSCOPE", "example/worker:v1", 1,
                Map.of("AGENTTEAMS_RUNTIME", "QWENPAW",
                        "AGENTTEAMS_RUNTIME_CONFIG_MAP", "release-agentteams-java-agent-runtime")));

        Deployment deployment = WorkerResourceFactory.deployment(worker);

        assertThat(deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv())
                .anySatisfy(env -> assertThat(env.getName()).isEqualTo("AGENTTEAMS_RUNTIME"))
                .filteredOn(env -> "AGENTTEAMS_RUNTIME".equals(env.getName()))
                .singleElement()
                .extracting(env -> env.getValue())
                .isEqualTo("AGENTSCOPE");
        assertThat(deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnvFrom())
                .isNotEmpty();
        assertThat(deployment.getSpec().getTemplate().getMetadata().getAnnotations())
                .containsEntry(WorkerResourceFactory.RUNTIME_ANNOTATION, "AGENTSCOPE")
                .doesNotContainKey(WorkerResourceFactory.SPEC_DIGEST_ANNOTATION)
                .doesNotContainKey(WorkerResourceFactory.CONFIG_REVISION_ANNOTATION);
    }

    @Test
    void envFromUsesRuntimeConfigMapNameProvidedByTheHelmReleaseBinding() {
        Worker worker = new Worker();
        worker.setMetadata(new ObjectMetaBuilder().withName("worker-runtime-config")
                .withNamespace("agentteams").build());
        worker.setSpec(new WorkerSpec("agent-a", "qwenpaw", "example/worker:v1", 1,
                Map.of("AGENTTEAMS_RUNTIME_CONFIG_MAP", "release-agentteams-java-agent-runtime")));

        Deployment deployment = WorkerResourceFactory.deployment(worker);

        assertThat(deployment.getSpec().getTemplate().getSpec().getContainers().get(0)
                .getEnvFrom().get(0).getConfigMapRef().getName())
                .isEqualTo("release-agentteams-java-agent-runtime");
    }

    @Test
    void keepsLegacyWorkersOnTheStableHelmRuntimeConfigMapByDefault() {
        Worker worker = new Worker();
        worker.setMetadata(new ObjectMetaBuilder().withName("worker-legacy")
                .withNamespace("agentteams").build());
        worker.setSpec(new WorkerSpec("agent-a", "qwenpaw", "example/worker:v1", 1, Map.of()));

        Deployment deployment = WorkerResourceFactory.deployment(worker);

        assertThat(deployment.getSpec().getTemplate().getSpec().getContainers().get(0)
                .getEnvFrom().get(0).getConfigMapRef().getName())
                .isEqualTo("agentteams-java-agent-runtime");
    }

    @Test
    void mountsConfiguredTlsSecretIntoWorkerDeployment() {
        Worker worker = new Worker();
        worker.setMetadata(new ObjectMetaBuilder().withName("worker-tls").withNamespace("agentteams").build());
        WorkerSpec spec = new WorkerSpec("agent-a", "qwenpaw", "example/worker:v1", 1,
                Map.of("AGENTTEAMS_RUNTIME_CONFIG_MAP", "release-agentteams-java-agent-runtime"));
        spec.setTlsSecret("agentteams-worker-mtls");
        worker.setSpec(spec);

        Deployment deployment = WorkerResourceFactory.deployment(worker);

        assertThat(deployment.getSpec().getTemplate().getSpec().getVolumes())
                .anySatisfy(volume -> assertThat(volume.getSecret().getSecretName()).isEqualTo("agentteams-worker-mtls"));
        assertThat(deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getVolumeMounts())
                .anySatisfy(mount -> assertThat(mount.getMountPath()).isEqualTo("/etc/agentteams/gateway-tls"));
        assertThat(deployment.getMetadata().getAnnotations())
                .containsEntry("secret.reloader.stakater.com/reload", "agentteams-worker-mtls");
    }
}
