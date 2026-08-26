package io.agentteams.operator;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.application.api.SandboxProfile;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskSandboxReconcilerTest {

    @Test
    void addsCleanupFinalizerAndCreatesOnlyControlledChildren() {
        FakeKubernetes fake = new FakeKubernetes();
        TaskSandbox sandbox = sandbox();

        var control = new TaskSandboxReconciler(fake.client(), factory()).reconcile(sandbox, null);

        assertThat(sandbox.hasFinalizer(TaskSandboxReconciler.FINALIZER)).isTrue();
        assertThat(fake.createdJob).isNotNull();
        assertThat(fake.createdService).isNotNull();
        assertThat(sandbox.getStatus().getPhase()).isEqualTo("PROVISIONING");
        assertThat(control.isUpdateResourceAndStatus()).isTrue();
    }

    @Test
    void deletesChildrenBeforeRemovingFinalizerOnCrDeletion() {
        FakeKubernetes fake = new FakeKubernetes();
        fake.job = runningJob();
        fake.service = factory().service(sandbox());
        TaskSandbox sandbox = sandbox();
        sandbox.getMetadata().setDeletionTimestamp("2026-08-26T08:00:00Z");
        sandbox.getMetadata().setFinalizers(new ArrayList<>(List.of(TaskSandboxReconciler.FINALIZER)));

        new TaskSandboxReconciler(fake.client(), factory()).reconcile(sandbox, null);

        assertThat(fake.jobDeleteCalls).isEqualTo(1);
        assertThat(fake.serviceDeleteCalls).isEqualTo(1);
        assertThat(sandbox.hasFinalizer(TaskSandboxReconciler.FINALIZER)).isTrue();

        fake.job = null;
        fake.service = null;
        new TaskSandboxReconciler(fake.client(), factory()).reconcile(sandbox, null);

        assertThat(sandbox.getStatus().getPhase()).isEqualTo("DESTROYED");
        assertThat(sandbox.hasFinalizer(TaskSandboxReconciler.FINALIZER)).isFalse();
    }

    @Test
    void terminationIntentDeletesChildrenWithoutCreatingReplacementWorkloads() {
        FakeKubernetes fake = new FakeKubernetes();
        fake.job = runningJob();
        fake.service = factory().service(sandbox());
        fake.terminationRequested = true;
        TaskSandbox sandbox = sandbox();
        sandbox.addFinalizer(TaskSandboxReconciler.FINALIZER);

        new TaskSandboxReconciler(fake.client(), factory()).reconcile(sandbox, null);

        assertThat(fake.jobDeleteCalls).isEqualTo(1);
        assertThat(fake.serviceDeleteCalls).isEqualTo(1);
        assertThat(fake.createdJob).isNull();
        assertThat(fake.createdService).isNull();
    }

    @Test
    void recreatesMissingServiceWithoutReplacingAnExistingJob() {
        FakeKubernetes fake = new FakeKubernetes();
        fake.job = runningJob();
        TaskSandbox sandbox = sandbox();

        new TaskSandboxReconciler(fake.client(), factory()).reconcile(sandbox, null);

        assertThat(fake.createdService).isNotNull();
        assertThat(fake.createdJob).isNull();
    }

    @Test
    void discardsAServiceFromAnOlderGenerationBeforeCreatingTheCurrentService() {
        FakeKubernetes fake = new FakeKubernetes();
        fake.service = factory().service(sandbox());
        TaskSandbox sandbox = sandbox();
        sandbox.getMetadata().setGeneration(4L);

        new TaskSandboxReconciler(fake.client(), factory()).reconcile(sandbox, null);

        assertThat(fake.serviceDeleteCalls).isEqualTo(1);
        assertThat(fake.createdService).isNull();

        fake.service = null;
        new TaskSandboxReconciler(fake.client(), factory()).reconcile(sandbox, null);

        assertThat(fake.createdService).isNotNull();
        assertThat(fake.createdService.getMetadata().getLabels())
                .containsEntry(TaskSandboxResourceFactory.GENERATION_LABEL, "4");
    }

    private static TaskSandboxResourceFactory factory() {
        return new TaskSandboxResourceFactory(Map.of(
                SandboxProfile.ISOLATED, "gvisor", SandboxProfile.HARDENED, "kata-qemu"));
    }

    private static TaskSandbox sandbox() {
        TaskSandbox sandbox = new TaskSandbox();
        sandbox.setMetadata(new ObjectMetaBuilder().withName("task-sandbox-isolated")
                .withNamespace("agentteams").withGeneration(3L).withUid("sandbox-uid").build());
        sandbox.setSpec(new TaskSandboxSpec("task-1", "attempt-1", SandboxProfile.ISOLATED,
                "gvisor", "ignored", 300, Map.of("cpu", "250m")));
        return sandbox;
    }

    private static Job runningJob() {
        return new JobBuilder().withMetadata(new ObjectMetaBuilder().withName("task-sandbox-isolated-job")
                .withNamespace("agentteams").withUid("job-uid").withLabels(Map.of(
                        "app.kubernetes.io/name", "agentteams-task-sandbox",
                        "app.kubernetes.io/managed-by", "agentteams-operator",
                        "agentteams.io/task-id", "task-1",
                        "agentteams.io/attempt-id", "attempt-1",
                        "agentteams.io/sandbox-profile", "ISOLATED",
                        TaskSandboxResourceFactory.GENERATION_LABEL, "3")).build())
                .withStatus(new JobStatusBuilder().withActive(1).build()).build();
    }

    private static final class FakeKubernetes implements InvocationHandler {
        private Job job;
        private Service service;
        private Endpoints endpoints;
        private GenericKubernetesResource terminationResource;
        private boolean terminationRequested;
        private Job createdJob;
        private Service createdService;
        private int jobDeleteCalls;
        private int serviceDeleteCalls;

        KubernetesClient client() {
            return proxy(KubernetesClient.class, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (name.equals("batch")) return proxy(method.getReturnType(), this, "batch");
            if (name.equals("jobs")) return proxy(method.getReturnType(), this, "jobs");
            if (name.equals("services")) return proxy(method.getReturnType(), this, "services");
            if (name.equals("endpoints")) return proxy(method.getReturnType(), this, "endpoints");
            if (name.equals("genericKubernetesResources")) return proxy(method.getReturnType(), this, "generic");
            if (name.equals("inNamespace") || name.equals("withLabels")) {
                return proxy(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class, this, state(proxy));
            }
            if (name.equals("resource")) {
                Object desired = args == null ? null : args[0];
                return proxy(resourceType(desired), this, desired);
            }
            if (name.equals("withName")) {
                return proxy(resourceType(state(proxy)), this,
                        new Named(String.valueOf(state(proxy)), String.valueOf(args[0])));
            }
            if (name.equals("createOrReplace")) {
                Object desired = state(proxy);
                if (desired instanceof Job value) createdJob = job = value;
                if (desired instanceof Service value) createdService = service = value;
                return desired;
            }
            if (name.equals("get")) {
                Object value = state(proxy);
                if (value instanceof String kind) {
                    return switch (kind) {
                        case "jobs" -> job;
                        case "services" -> service;
                        case "endpoints" -> endpoints;
                        case "generic" -> terminationResource();
                        default -> null;
                    };
                }
                if (value instanceof Named named) {
                    Object result = switch (named.kind()) {
                        case "jobs" -> job;
                        case "services" -> service;
                        case "endpoints" -> endpoints;
                        case "generic" -> terminationResource();
                        default -> null;
                    };
                    return result;
                }
            }
            if (name.equals("delete")) {
                Object value = state(proxy);
                String kind = value instanceof Named named ? named.kind() : String.valueOf(value);
                if (kind.equals("jobs")) jobDeleteCalls++;
                if (kind.equals("services")) serviceDeleteCalls++;
                return method.getReturnType() == java.util.List.class ? java.util.List.of() : true;
            }
            if (name.equals("list")) return null;
            if (name.equals("toString")) return "fake-kubernetes";
            if (method.getReturnType().isInterface()) return proxy(method.getReturnType(), this, state(proxy));
            return defaultValue(method.getReturnType());
        }

        private GenericKubernetesResource terminationResource() {
            if (!terminationRequested) return null;
            GenericKubernetesResource resource = new GenericKubernetesResource();
            resource.setAdditionalProperties(new LinkedHashMap<>(Map.of(
                    "spec", Map.of("terminationRequested", true))));
            return resource;
        }

        private static Object state(Object proxy) {
            return Proxy.getInvocationHandler(proxy) instanceof StateCarrier carrier ? carrier.state() : null;
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<?> type, InvocationHandler handler, Object state) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type, StateCarrier.class},
                    new StateHandler(handler, state));
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<?> type, InvocationHandler handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
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
            if (type == char.class) return '\0';
            return null;
        }

        private static Class<?> resourceType(Object state) {
            if (state instanceof Job) return io.fabric8.kubernetes.client.dsl.ScalableResource.class;
            if (state instanceof Service) return io.fabric8.kubernetes.client.dsl.ServiceResource.class;
            String kind = state instanceof Named named ? named.kind() : String.valueOf(state);
            return switch (kind) {
                case "jobs" -> io.fabric8.kubernetes.client.dsl.ScalableResource.class;
                case "services" -> io.fabric8.kubernetes.client.dsl.ServiceResource.class;
                default -> io.fabric8.kubernetes.client.dsl.Resource.class;
            };
        }

        private record Named(String kind, String name) { }

        private interface StateCarrier {
            Object state();
        }

        private static final class StateHandler implements InvocationHandler, StateCarrier {
            private final InvocationHandler delegate;
            private final Object state;

            StateHandler(InvocationHandler delegate, Object state) {
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
    }
}
