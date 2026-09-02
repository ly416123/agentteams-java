package io.agentteams.controlplane.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KubernetesWorkerCrdProvisionerTest {
    @Test
    void rendersAnExplicitWorkerCrdWithScopedRuntimeMetadata() {
        UUID workerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        WorkerCrdProvisioner.Request request = new WorkerCrdProvisioner.Request(
                workerId, "qwenpaw", "deepseek", "deepseek-chat", "tenant-a", "project-a", "team-a",
                "sha256:template", "cfg-1", "secret-generation-1", "ghcr.io/ly416123/agentteams-agent-worker:latest",
                1, "agentteams-agentteams-java-gateway", 9090,
                "http://agentteams-agentteams-java-control-plane:8080", "http://qwenpaw:8088", "", Map.of());

        GenericKubernetesResource resource = KubernetesWorkerCrdProvisioner.resource("agentteams", request);

        assertThat(resource.getApiVersion()).isEqualTo("agentteams.io/v1alpha1");
        assertThat(resource.getKind()).isEqualTo("Worker");
        assertThat(resource.getMetadata().getName()).isEqualTo("worker-11111111111111111111111111111111");
        assertThat(resource.getMetadata().getNamespace()).isEqualTo("agentteams");
        assertThat(resource.<Map<String, Object>>get("spec"))
                .containsEntry("agentId", workerId.toString())
                .containsEntry("runtime", "qwenpaw")
                .containsEntry("specDigest", "sha256:template")
                .containsEntry("configRevision", "cfg-1")
                .containsEntry("secretGeneration", "secret-generation-1");
        Map<String, Object> spec = resource.get("spec");
        Map<?, ?> environment = (Map<?, ?>) spec.get("env");
        assertThat(environment.get("AGENTTEAMS_MODEL_PROVIDER")).isEqualTo("deepseek");
        assertThat(environment.get("AGENTTEAMS_MODEL")).isEqualTo("deepseek-chat");
        assertThat(environment.get("AGENTTEAMS_SCOPE_TENANT")).isEqualTo("tenant-a");
        assertThat(environment.get("AGENTTEAMS_SCOPE_PROJECT")).isEqualTo("project-a");
    }
}
