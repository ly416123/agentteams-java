package io.agentteams.operator;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentStrategyBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpecBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatusBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerReconcilerTest {

    @Test
    void doesNotReplaceDeploymentWhenDesiredWorkerFieldsAlreadyMatch() {
        Worker worker = new Worker();
        worker.setMetadata(new ObjectMetaBuilder().withName("worker-a").withNamespace("agentteams")
                .withGeneration(7L).withUid("worker-uid").build());
        worker.setSpec(new WorkerSpec("agent-a", "agentscope", "example/worker:v1", 1,
                Map.of(), "", "sha256:abc", "runtime-1", ""));
        Deployment desired = WorkerResourceFactory.deployment(worker);
        var desiredPod = desired.getSpec().getTemplate().getSpec();
        var container = new ContainerBuilder(desiredPod.getContainers().get(0))
                .withTerminationMessagePath("/dev/termination-log").withTerminationMessagePolicy("File").build();
        var podSpec = new PodSpecBuilder(desiredPod).withContainers(container)
                .withRestartPolicy("Always").withTerminationGracePeriodSeconds(30L)
                .withDnsPolicy("ClusterFirst").withSchedulerName("default-scheduler").build();
        var template = new io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder(desired.getSpec().getTemplate())
                .withSpec(podSpec).build();
        Deployment current = new DeploymentBuilder(desired)
                .editMetadata().withGeneration(67419L).endMetadata()
                .editSpec().withTemplate(template)
                .withStrategy(new DeploymentStrategyBuilder().withType("RollingUpdate").build())
                .endSpec()
                .withStatus(new DeploymentStatusBuilder().withObservedGeneration(67419L).withUpdatedReplicas(1)
                        .withReadyReplicas(1).withAvailableReplicas(1).build())
                .build();
        FakeKubernetes fake = new FakeKubernetes(current);

        new WorkerReconciler(fake.client()).reconcile(worker, null);

        assertThat(fake.deploymentReplaceCalls).isZero();
        assertThat(worker.getStatus().getPhase()).isEqualTo("Ready");
    }

    @Test
    void replacesDeploymentWhenDesiredWorkerImageAndVersionChange() {
        Worker previousWorker = worker("example/worker:v1", "sha256:v1", "runtime-1");
        Deployment current = WorkerResourceFactory.deployment(previousWorker);
        current.setStatus(new DeploymentStatusBuilder().withObservedGeneration(3L).withUpdatedReplicas(1)
                .withReadyReplicas(1).withAvailableReplicas(1).build());
        FakeKubernetes fake = new FakeKubernetes(current);
        Worker desiredWorker = worker("example/worker:v2", "sha256:v2", "runtime-2");

        new WorkerReconciler(fake.client()).reconcile(desiredWorker, null);

        assertThat(fake.deploymentReplaceCalls).isEqualTo(1);
        assertThat(fake.deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getImage())
                .isEqualTo("example/worker:v2");
    }

    @Test
    void projectsObservedWorkerVersionOnlyWhenDeploymentIsReady() {
        Worker worker = new Worker();
        worker.setMetadata(new ObjectMetaBuilder().withName("worker-a").withGeneration(7L).build());
        worker.setSpec(new WorkerSpec("agent-a", "agentscope", "example/worker@sha256:abc", 2,
                Map.of(), "tls-secret", "sha256:abc", "runtime-8", "secret-3"));

        Deployment deployment = new DeploymentBuilder()
                .withSpec(new DeploymentSpecBuilder().withTemplate(new PodTemplateSpecBuilder()
                        .withMetadata(new ObjectMetaBuilder().withAnnotations(Map.of(
                                WorkerResourceFactory.SPEC_DIGEST_ANNOTATION, "sha256:abc",
                                WorkerResourceFactory.RUNTIME_ANNOTATION, "AGENTSCOPE",
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
        assertThat(status.getObservedRuntime()).isEqualTo("AGENTSCOPE");
        assertThat(status.getObservedConfigRevision()).isEqualTo("runtime-8");
        assertThat(status.getObservedSecretGeneration()).isEqualTo("secret-3");
    }

    @Test
    void treatsReadyDeploymentAsCompleteWhenWorkerCrGenerationIsOlderThanDeploymentHistory() {
        Worker worker = new Worker();
        worker.setMetadata(new ObjectMetaBuilder().withName("worker-a").withGeneration(20L).build());
        worker.setSpec(new WorkerSpec("agent-a", "agentscope", "example/worker:v2", 1,
                Map.of(), "", "digest-v2", "runtime-2", "secret-2"));

        Deployment deployment = new DeploymentBuilder()
                .withMetadata(new ObjectMetaBuilder().withGeneration(2L).build())
                .withSpec(new DeploymentSpecBuilder().withTemplate(new PodTemplateSpecBuilder()
                        .withMetadata(new ObjectMetaBuilder().withAnnotations(Map.of(
                                WorkerResourceFactory.SPEC_DIGEST_ANNOTATION, "digest-v2",
                                WorkerResourceFactory.RUNTIME_ANNOTATION, "AGENTSCOPE",
                                WorkerResourceFactory.CONFIG_REVISION_ANNOTATION, "runtime-2",
                                WorkerResourceFactory.SECRET_GENERATION_ANNOTATION, "secret-2"))
                                .build()).build()).build())
                .withStatus(new DeploymentStatusBuilder().withObservedGeneration(2L).withUpdatedReplicas(1)
                        .withReadyReplicas(1).withAvailableReplicas(1).build())
                .build();

        WorkerStatus status = WorkerReconciler.statusFor(worker, deployment);

        assertThat(status.getPhase()).isEqualTo("Ready");
        assertThat(status.getObservedSpecDigest()).isEqualTo("digest-v2");
    }

    @Test
    void waitsUntilDeploymentControllerObservesTheCurrentDeploymentGeneration() {
        Worker worker = new Worker();
        worker.setMetadata(new ObjectMetaBuilder().withName("worker-a").withGeneration(7L).build());
        worker.setSpec(new WorkerSpec("agent-a", "agentscope", "example/worker:v1", 1,
                Map.of(), "", "sha256:v1", "runtime-1", ""));

        Deployment deployment = new DeploymentBuilder()
                .withMetadata(new ObjectMetaBuilder().withGeneration(67420L).build())
                .withSpec(new DeploymentSpecBuilder().withTemplate(new PodTemplateSpecBuilder()
                        .withMetadata(new ObjectMetaBuilder().withAnnotations(Map.of(
                                WorkerResourceFactory.SPEC_DIGEST_ANNOTATION, "sha256:v1",
                                WorkerResourceFactory.RUNTIME_ANNOTATION, "AGENTSCOPE",
                                WorkerResourceFactory.CONFIG_REVISION_ANNOTATION, "runtime-1"))
                                .build()).build()).build())
                .withStatus(new DeploymentStatusBuilder().withObservedGeneration(67419L).withUpdatedReplicas(1)
                        .withReadyReplicas(1).withAvailableReplicas(1).build())
                .build();

        WorkerStatus status = WorkerReconciler.statusFor(worker, deployment);

        assertThat(status.getPhase()).isEqualTo("Progressing");
        assertThat(status.getObservedSpecDigest()).isNull();
    }

    @Test
    void marksWorkerProgressingWhenDeploymentHasNotConfirmedDesiredReplicas() {
        Worker worker = new Worker();
        worker.setMetadata(new ObjectMetaBuilder().withName("worker-a").withGeneration(8L).build());
        worker.setSpec(new WorkerSpec("agent-a", "agentscope", "example/worker@sha256:def", 2,
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
        worker.setSpec(new WorkerSpec("agent-a", "agentscope", "example/worker@sha256:new", 1,
                Map.of(), "", "sha256:new", "runtime-10", "secret-5"));

        Deployment deployment = new DeploymentBuilder()
                .withSpec(new DeploymentSpecBuilder().withTemplate(new PodTemplateSpecBuilder()
                        .withMetadata(new ObjectMetaBuilder().withAnnotations(Map.of(
                                WorkerResourceFactory.SPEC_DIGEST_ANNOTATION, "sha256:old",
                                WorkerResourceFactory.RUNTIME_ANNOTATION, "AGENTSCOPE",
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
        worker.setSpec(new WorkerSpec("agent-a", "agentscope", "example/worker@sha256:new", 2,
                Map.of(), "", "sha256:new", "runtime-11", "secret-6"));
        WorkerStatus previous = new WorkerStatus();
        previous.setObservedSpecDigest("sha256:old");
        previous.setObservedRuntime("agentscope");
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
        worker.setSpec(new WorkerSpec("11111111-1111-1111-1111-111111111111", "agentscope",
                "example/worker:v1", 1, Map.of(), ""));

        WorkerReconciler.applyDirective(worker, new WorkerOperationDirective(
                "22222222-2222-2222-2222-222222222222",
                "11111111-1111-1111-1111-111111111111", "TERMINATE", "", "", "", ""));

        assertThat(worker.getSpec().replicas()).isZero();
    }

    private static Worker worker(String image, String digest, String configRevision) {
        Worker worker = new Worker();
        worker.setMetadata(new ObjectMetaBuilder().withName("worker-a").withNamespace("agentteams")
                .withGeneration(7L).withUid("worker-uid").build());
        worker.setSpec(new WorkerSpec("agent-a", "agentscope", image, 1,
                Map.of(), "", digest, configRevision, ""));
        return worker;
    }

    private static final class FakeKubernetes implements InvocationHandler {
        private Deployment deployment;
        private int deploymentReplaceCalls;

        FakeKubernetes(Deployment deployment) {
            this.deployment = deployment;
        }

        KubernetesClient client() {
            return proxy(KubernetesClient.class, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (name.equals("apps")) return proxy(method.getReturnType(), this, "apps");
            if (name.equals("services")) return proxy(method.getReturnType(), this, "services");
            if (name.equals("deployments")) return proxy(method.getReturnType(), this, "deployments");
            if (name.equals("inNamespace")) {
                return proxy(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class, this, state(proxy));
            }
            if (name.equals("resource")) return proxy(resourceType(args[0]), this, args[0]);
            if (name.equals("withName")) {
                return proxy(resourceType(state(proxy)), this, new Named(String.valueOf(state(proxy)),
                        String.valueOf(args[0])));
            }
            if (name.equals("get")) {
                Object state = state(proxy);
                if (state instanceof Named named && named.kind().equals("deployments")) return deployment;
                return null;
            }
            if (name.equals("createOrReplace")) {
                Object state = state(proxy);
                if (state instanceof Deployment desired) {
                    deploymentReplaceCalls++;
                    deployment = desired;
                    return desired;
                }
                return state;
            }
            if (name.equals("toString")) return "fake-kubernetes";
            if (method.getReturnType().isInterface()) return proxy(method.getReturnType(), this, state(proxy));
            return defaultValue(method.getReturnType());
        }

        @SuppressWarnings("unchecked")
        private <T> T proxy(Class<?> type, InvocationHandler handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
        }

        @SuppressWarnings("unchecked")
        private <T> T proxy(Class<?> type, InvocationHandler handler, Object state) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type, StateCarrier.class},
                    new StateHandler(handler, state));
        }

        private static Object state(Object proxy) {
            return Proxy.getInvocationHandler(proxy) instanceof StateCarrier carrier ? carrier.state() : null;
        }

        private static Class<?> resourceType(Object state) {
            if (state instanceof Deployment) return io.fabric8.kubernetes.client.dsl.RollableScalableResource.class;
            if (state instanceof io.fabric8.kubernetes.api.model.Service) {
                return io.fabric8.kubernetes.client.dsl.ServiceResource.class;
            }
            String kind = state instanceof Named named ? named.kind() : String.valueOf(state);
            return kind.equals("services") ? io.fabric8.kubernetes.client.dsl.ServiceResource.class
                    : io.fabric8.kubernetes.client.dsl.RollableScalableResource.class;
        }

        private record Named(String kind, String name) { }

        private interface StateCarrier {
            Object state();
        }

        private static final class StateHandler implements InvocationHandler, StateCarrier {
            private final InvocationHandler delegate;
            private final Object state;

            private StateHandler(InvocationHandler delegate, Object state) {
                this.delegate = delegate;
                this.state = state;
            }

            @Override
            public Object state() { return state; }

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                return delegate.invoke(proxy, method, args);
            }
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == double.class) return 0D;
            if (type == float.class) return 0F;
            if (type == short.class) return (short) 0;
            if (type == byte.class) return (byte) 0;
            if (type == char.class) return (char) 0;
            return null;
        }
    }
}
