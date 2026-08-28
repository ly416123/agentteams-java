package io.agentteams.operator;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Minimal in-memory Fabric8 DSL used by reconciler behavior tests. */
final class ReconcilerBehaviorTestSupport implements InvocationHandler {
    Deployment deployment;
    Service service;
    ConfigMap configMap;
    int deploymentWrites;
    int serviceWrites;
    int configMapWrites;

    KubernetesClient client() {
        return proxy(KubernetesClient.class, this, null);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if (name.equals("apps") || name.equals("deployments") || name.equals("services")
                || name.equals("configMaps")) {
            return proxy(method.getReturnType(), this, name.equals("apps") ? "apps" : name);
        }
        if (name.equals("inNamespace") || name.equals("withLabels")) {
            return proxy(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class, this, state(proxy));
        }
        if (name.equals("withName")) {
            return proxy(resourceType(state(proxy)), this,
                    new Named(String.valueOf(state(proxy)), String.valueOf(args[0])));
        }
        if (name.equals("resource")) {
            Object desired = args == null ? null : args[0];
            return proxy(resourceType(desired), this, desired);
        }
        if (name.equals("createOrReplace")) {
            Object desired = state(proxy);
            if (desired instanceof Deployment value) {
                deployment = value;
                deploymentWrites++;
            } else if (desired instanceof Service value) {
                service = value;
                serviceWrites++;
            } else if (desired instanceof ConfigMap value) {
                configMap = value;
                configMapWrites++;
            }
            return desired;
        }
        if (name.equals("get")) {
            Object current = state(proxy);
            String kind = current instanceof Named named ? named.kind() : String.valueOf(current);
            return switch (kind) {
                case "deployments" -> deployment;
                case "services" -> service;
                case "configMaps" -> configMap;
                default -> null;
            };
        }
        if (name.equals("toString")) return "reconciler-behavior-fake";
        if (method.getReturnType().isInterface()) {
            return proxy(method.getReturnType(), this, state(proxy));
        }
        return defaultValue(method.getReturnType());
    }

    private static Object state(Object proxy) {
        return Proxy.getInvocationHandler(proxy) instanceof StateCarrier carrier ? carrier.state() : null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<?> type, InvocationHandler handler, Object state) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(),
                new Class<?>[]{type, StateCarrier.class}, new StateHandler(handler, state));
    }

    private static Class<?> resourceType(Object state) {
        if (state instanceof Deployment) return io.fabric8.kubernetes.client.dsl.RollableScalableResource.class;
        if (state instanceof Service) return io.fabric8.kubernetes.client.dsl.ServiceResource.class;
        if (state instanceof ConfigMap) return io.fabric8.kubernetes.client.dsl.Resource.class;
        String kind = state instanceof Named named ? named.kind() : String.valueOf(state);
        return switch (kind) {
            case "deployments" -> io.fabric8.kubernetes.client.dsl.RollableScalableResource.class;
            case "services" -> io.fabric8.kubernetes.client.dsl.ServiceResource.class;
            default -> io.fabric8.kubernetes.client.dsl.Resource.class;
        };
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
        public Object state() {
            return state;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return delegate.invoke(proxy, method, args);
        }
    }
}
