package io.agentteams.controlplane.security;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kubernetes-backed resolver that returns only metadata/value presence state.
 * The credential bytes are never exposed through this SPI or written to logs.
 */
public final class KubernetesSecretResolver implements SecretResolver, AutoCloseable {

    private final KubernetesSecretReader reader;
    private final SecretResolverProperties properties;
    private final ExecutorService executor;

    public KubernetesSecretResolver(KubernetesClient client, SecretResolverProperties properties) {
        this(new Fabric8SecretReader(client), properties,
                Executors.newCachedThreadPool(new ResolverThreadFactory()));
    }

    KubernetesSecretResolver(KubernetesSecretReader reader, SecretResolverProperties properties,
            ExecutorService executor) {
        this.reader = java.util.Objects.requireNonNull(reader, "reader");
        this.properties = java.util.Objects.requireNonNull(properties, "properties");
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
        properties.validateKubernetes();
    }

    @Override
    public Resolution resolve(String credentialRef) {
        KubernetesSecretReference reference = KubernetesSecretReference.parse(credentialRef);
        if (reference == null) {
            return new Resolution(credentialRef == null || credentialRef.isBlank()
                    ? Status.MISSING : Status.INVALID_REFERENCE);
        }
        if (!properties.getAllowedNamespaces().contains(reference.namespace())
                || !properties.getAllowedNames().contains(reference.name())
                || !properties.getAllowedKeys().contains(reference.key())) {
            return new Resolution(Status.INVALID_REFERENCE);
        }

        Future<KubernetesSecretReader.ValueState> lookup = executor.submit(
                () -> reader.read(reference.namespace(), reference.name(), reference.key()));
        try {
            Duration timeout = properties.getTimeout();
            KubernetesSecretReader.ValueState state = lookup.get(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
            return new Resolution(state == KubernetesSecretReader.ValueState.PRESENT
                    ? Status.RESOLVED : Status.MISSING);
        } catch (TimeoutException error) {
            lookup.cancel(true);
            return new Resolution(Status.UNAVAILABLE);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            lookup.cancel(true);
            return new Resolution(Status.UNAVAILABLE);
        } catch (CancellationException | ExecutionException error) {
            return new Resolution(classify(error));
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static Status classify(Throwable error) {
        Throwable cause = error;
        if (error instanceof ExecutionException && error.getCause() != null) {
            cause = error.getCause();
        }
        if (cause instanceof KubernetesClientException clientError && clientError.getCode() == 404) {
            return Status.MISSING;
        }
        return Status.UNAVAILABLE;
    }

    private static final class Fabric8SecretReader implements KubernetesSecretReader {
        private final KubernetesClient client;

        private Fabric8SecretReader(KubernetesClient client) {
            this.client = java.util.Objects.requireNonNull(client, "client");
        }

        @Override
        public ValueState read(String namespace, String name, String key) {
            Secret secret = client.secrets().inNamespace(namespace).withName(name).get();
            if (secret == null || secret.getData() == null || !secret.getData().containsKey(key)) {
                return ValueState.MISSING;
            }
            String value = secret.getData().get(key);
            return value == null || value.isEmpty() ? ValueState.MISSING : ValueState.PRESENT;
        }
    }

    private static final class ResolverThreadFactory implements ThreadFactory {
        private static final AtomicInteger IDS = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "agentteams-secret-resolver-" + IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
