package io.agentteams.operator;

import java.util.Map;
import java.util.Objects;

/** Kubernetes-friendly mutable spec with immutable-style accessors for application code. */
public final class WorkerSpec {
    private String agentId;
    private String runtime;
    private String image;
    private int replicas;
    private Map<String, String> env;
    private String tlsSecret;
    private String specDigest;
    private String configRevision;
    private String secretGeneration;

    public WorkerSpec() {
        this.agentId = "";
        this.runtime = "";
        this.image = "";
        this.replicas = 1;
        this.env = Map.of();
        this.tlsSecret = "";
        this.specDigest = "";
        this.configRevision = "";
        this.secretGeneration = "";
    }

    public WorkerSpec(String agentId, String runtime, String image, int replicas, Map<String, String> env) {
        this(agentId, runtime, image, replicas, env, "");
    }

    public WorkerSpec(String agentId, String runtime, String image, int replicas, Map<String, String> env,
            String tlsSecret) {
        this(agentId, runtime, image, replicas, env, tlsSecret, "", "", "");
    }

    public WorkerSpec(String agentId, String runtime, String image, int replicas, Map<String, String> env,
            String tlsSecret, String specDigest, String configRevision, String secretGeneration) {
        setAgentId(agentId);
        setRuntime(runtime);
        setImage(image);
        setReplicas(replicas);
        setEnv(env);
        setTlsSecret(tlsSecret);
        setSpecDigest(specDigest);
        setConfigRevision(configRevision);
        setSecretGeneration(secretGeneration);
    }

    public String agentId() { return agentId; }
    public String runtime() { return runtime; }
    public String image() { return image; }
    public int replicas() { return replicas; }
    public Map<String, String> env() { return env; }
    public String tlsSecret() { return tlsSecret; }
    public String specDigest() { return specDigest; }
    public String configRevision() { return configRevision; }
    public String secretGeneration() { return secretGeneration; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String value) { agentId = requireText(value, "agentId"); }
    public String getRuntime() { return runtime; }
    public void setRuntime(String value) { runtime = requireText(value, "runtime"); }
    public String getImage() { return image; }
    public void setImage(String value) { image = requireText(value, "image"); }
    public int getReplicas() { return replicas; }
    public void setReplicas(int value) {
        if (value < 0) throw new IllegalArgumentException("replicas must not be negative");
        replicas = value;
    }
    public Map<String, String> getEnv() { return env; }
    public void setEnv(Map<String, String> value) { env = Map.copyOf(Objects.requireNonNull(value, "env")); }
    public String getTlsSecret() { return tlsSecret; }
    public void setTlsSecret(String value) { tlsSecret = value == null ? "" : value.trim(); }
    public String getSpecDigest() { return specDigest; }
    public void setSpecDigest(String value) { specDigest = optionalText(value); }
    public String getConfigRevision() { return configRevision; }
    public void setConfigRevision(String value) { configRevision = optionalText(value); }
    public String getSecretGeneration() { return secretGeneration; }
    public void setSecretGeneration(String value) { secretGeneration = optionalText(value); }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String optionalText(String value) {
        return value == null ? "" : value.trim();
    }
}
