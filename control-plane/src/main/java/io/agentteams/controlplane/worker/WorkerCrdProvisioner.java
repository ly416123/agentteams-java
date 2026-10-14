package io.agentteams.controlplane.worker;

import io.agentteams.application.api.ExecutionRuntime;
import java.util.Map;
import java.util.UUID;

/** Creates the Kubernetes Worker resource after a logical Worker is explicitly instantiated. */
@FunctionalInterface
public interface WorkerCrdProvisioner {
    void provision(Request request);

    record Request(UUID workerId, String runtime, String modelProvider, String model,
            String tenantId, String projectId, String team,
            String specDigest, String configRevision, String secretGeneration,
            String image, int replicas, String gatewayHost, int gatewayPort,
            String configManifestBaseUrl, String tlsSecret,
            Map<String, String> environment, Map<String, SecretEnvRef> secretEnv,
            String chatGptPlanTokenSecret) {
        public Request {
            if (workerId == null) throw new IllegalArgumentException("workerId is required");
            if (runtime == null || runtime.isBlank()) throw new IllegalArgumentException("runtime is required");
            if (ExecutionRuntime.from(runtime) != ExecutionRuntime.AGENTSCOPE) {
                throw new IllegalArgumentException("only AGENTSCOPE runtime is supported");
            }
            if (image == null || image.isBlank()) throw new IllegalArgumentException("image is required");
            if (replicas < 1) throw new IllegalArgumentException("replicas must be positive");
            environment = environment == null ? Map.of() : Map.copyOf(environment);
            secretEnv = secretEnv == null ? Map.of() : Map.copyOf(secretEnv);
        }

        public Request(UUID workerId, String runtime, String modelProvider, String model,
                String tenantId, String projectId, String team,
                String specDigest, String configRevision, String secretGeneration,
                String image, int replicas, String gatewayHost, int gatewayPort,
                String configManifestBaseUrl, String tlsSecret,
                Map<String, String> environment) {
            this(workerId, runtime, modelProvider, model, tenantId, projectId, team,
                    specDigest, configRevision, secretGeneration, image, replicas,
                    gatewayHost, gatewayPort, configManifestBaseUrl,
                    tlsSecret, environment, Map.of(), "");
        }

        public Request(UUID workerId, String runtime, String modelProvider, String model,
                String tenantId, String projectId, String team,
                String specDigest, String configRevision, String secretGeneration,
                String image, int replicas, String gatewayHost, int gatewayPort,
                String configManifestBaseUrl, String tlsSecret,
                Map<String, String> environment, Map<String, SecretEnvRef> secretEnv) {
            this(workerId, runtime, modelProvider, model, tenantId, projectId, team,
                    specDigest, configRevision, secretGeneration, image, replicas,
                    gatewayHost, gatewayPort, configManifestBaseUrl, tlsSecret,
                    environment, secretEnv, "");
        }
    }

    /** Reference to one key inside a Secret; the operator materializes it as a secretKeyRef env var. */
    record SecretEnvRef(String secret, String key) {
        public SecretEnvRef {
            if (secret == null || secret.isBlank()) {
                throw new IllegalArgumentException("secretEnv secret name must not be blank");
            }
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("secretEnv key must not be blank");
            }
            secret = secret.trim();
            key = key.trim();
        }
    }
}
