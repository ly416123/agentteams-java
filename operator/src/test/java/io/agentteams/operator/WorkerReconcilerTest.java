package io.agentteams.operator;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpecBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatusBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerReconcilerTest {

    @Test
    void projectsObservedWorkerVersionOnlyWhenDeploymentIsReady() {
        Worker worker = new Worker();
        worker.setMetadata(new ObjectMetaBuilder().withName("worker-a").withGeneration(7L).build());
        worker.setSpec(new WorkerSpec("agent-a", "qwenpaw", "example/worker@sha256:abc", 2,
                Map.of(), "tls-secret", "sha256:abc", "runtime-8", "secret-3"));

        Deployment deployment = new DeploymentBuilder()
                .withSpec(new DeploymentSpecBuilder().withTemplate(new PodTemplateSpecBuilder()
                        .withMetadata(new ObjectMetaBuilder().withAnnotations(Map.of(
                                WorkerResourceFactory.SPEC_DIGEST_ANNOTATION, "sha256:abc",
                                WorkerResourceFactory.RUNTIME_ANNOTATION, "qwenpaw",
                                WorkerResourceFactory.CONFIG_REVISION_ANNOTATION, "runtime-8",
                                WorkerResourceFactory.SECRET_GENERATION_ANNOTATION, "secret-3"))
                                .build()).build()).build())
                .withStatus(new DeploymentStatusBuilder().withObservedGeneration(7L).withUpdatedReplicas(2)
                        .withReadyReplicas(2).withAvailableReplicas(2).build())
                .build();

        WorkerStatus status = WorkerReconciler.statusFor(worker, deployment);

        assertThat(status.getPhase()).isEqualTo("Ready");
        assertThat(status.getObservedGeneration()).isEqualTo(7L);
        assertThat(status.getReadyReplicas()).isEqualTo(2);
        assertThat(status.getObservedSpecDigest()).isEqualTo("sha256:abc");
        assertThat(status.getObservedRuntime()).isEqualTo("qwenpaw");
        assertThat(status.getObservedConfigRevision()).isEqualTo("runtime-8");
        assertThat(status.getObservedSecretGeneration()).isEqualTo("secret-3");
    }

    @Test
    void marksWorkerProgressingWhenDeploymentHasNotConfirmedDesiredReplicas() {
        Worker worker = new Worker();
        worker.setMetadata(new ObjectMetaBuilder().withName("worker-a").withGeneration(8L).build());
        worker.setSpec(new WorkerSpec("agent-a", "qwenpaw", "example/worker@sha256:def", 2,
                Map.of(), "", "sha256:def", "runtime-9", "secret-4"));

        WorkerStatus status = WorkerReconciler.statusFor(worker, null);

        assertThat(status.getPhase()).isEqualTo("Progressing");
        assertThat(status.getReadyReplicas()).isZero();
        assertThat(status.getObservedSpecDigest()).isNull();
        assertThat(status.getMessage()).contains("readiness");
    }

    @Test
    void doesNotReportReadyWhenDeploymentVersionDiffersFromWorkerSpec() {
        Worker worker = new Worker();
        worker.setMetadata(new ObjectMetaBuilder().withName("worker-a").withGeneration(9L).build());
        worker.setSpec(new WorkerSpec("agent-a", "qwenpaw", "example/worker@sha256:new", 1,
                Map.of(), "", "sha256:new", "runtime-10", "secret-5"));

        Deployment deployment = new DeploymentBuilder()
                .withSpec(new DeploymentSpecBuilder().withTemplate(new PodTemplateSpecBuilder()
                        .withMetadata(new ObjectMetaBuilder().withAnnotations(Map.of(
                                WorkerResourceFactory.SPEC_DIGEST_ANNOTATION, "sha256:old",
                                WorkerResourceFactory.RUNTIME_ANNOTATION, "qwenpaw",
                                WorkerResourceFactory.CONFIG_REVISION_ANNOTATION, "runtime-10",
                                WorkerResourceFactory.SECRET_GENERATION_ANNOTATION, "secret-5"))
                                .build()).build()).build())
                .withStatus(new DeploymentStatusBuilder().withObservedGeneration(9L).withUpdatedReplicas(1)
                        .withReadyReplicas(1).withAvailableReplicas(1).build())
                .build();

        WorkerStatus status = WorkerReconciler.statusFor(worker, deployment);

        assertThat(status.getPhase()).isEqualTo("Progressing");
        assertThat(status.getObservedSpecDigest()).isEqualTo("sha256:old");
    }

    @Test
    void preservesLastObservedVersionWhileNewDeploymentIsStillProgressing() {
        Worker worker = new Worker();
        worker.setMetadata(new ObjectMetaBuilder().withName("worker-a").withGeneration(10L).build());
        worker.setSpec(new WorkerSpec("agent-a", "qwenpaw", "example/worker@sha256:new", 2,
                Map.of(), "", "sha256:new", "runtime-11", "secret-6"));
        WorkerStatus previous = new WorkerStatus();
        previous.setObservedSpecDigest("sha256:old");
        previous.setObservedRuntime("qwenpaw");
        previous.setObservedConfigRevision("runtime-10");
        previous.setObservedSecretGeneration("secret-5");
        worker.setStatus(previous);

        Deployment deployment = new DeploymentBuilder()
                .withStatus(new DeploymentStatusBuilder().withObservedGeneration(10L).withUpdatedReplicas(1)
                        .withReadyReplicas(1).withAvailableReplicas(1).build())
                .build();

        WorkerStatus status = WorkerReconciler.statusFor(worker, deployment);

        assertThat(status.getPhase()).isEqualTo("Progressing");
        assertThat(status.getObservedSpecDigest()).isEqualTo("sha256:old");
        assertThat(status.getObservedConfigRevision()).isEqualTo("runtime-10");
    }

    @Test
    void appliesTerminateDirectiveByScalingWorkerDeploymentToZero() {
        Worker worker = new Worker();
        worker.setMetadata(new ObjectMetaBuilder().withName("worker-a").withGeneration(11L).build());
        worker.setSpec(new WorkerSpec("11111111-1111-1111-1111-111111111111", "qwenpaw",
                "example/worker:v1", 1, Map.of(), ""));

        WorkerReconciler.applyDirective(worker, new WorkerOperationDirective(
                "22222222-2222-2222-2222-222222222222",
                "11111111-1111-1111-1111-111111111111", "TERMINATE", "", "", "", ""));

        assertThat(worker.getSpec().replicas()).isZero();
    }
}
