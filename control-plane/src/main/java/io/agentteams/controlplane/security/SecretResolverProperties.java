package io.agentteams.controlplane.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned secret resolver settings. Values never contain credential material. */
@ConfigurationProperties(prefix = "agentteams.security.secret-resolver")
public class SecretResolverProperties {

    private Backend backend = Backend.VALIDATION_ONLY;
    private Duration timeout = Duration.ofSeconds(2);
    private List<String> allowedNamespaces = new ArrayList<>();
    private List<String> allowedNames = new ArrayList<>();
    private List<String> allowedKeys = new ArrayList<>();

    public void validateKubernetes() {
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("secret resolver timeout must be between 1ms and 60s");
        }
        requireAllowlist(allowedNamespaces, "namespaces");
        requireAllowlist(allowedNames, "names");
        requireAllowlist(allowedKeys, "keys");
    }

    private static void requireAllowlist(List<String> values, String label) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("secret resolver Kubernetes " + label + " allowlist must not be empty");
        }
    }

    public Backend getBackend() {
        return backend;
    }

    public void setBackend(Backend backend) {
        this.backend = backend;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public List<String> getAllowedNamespaces() {
        return List.copyOf(allowedNamespaces);
    }

    public void setAllowedNamespaces(List<String> allowedNamespaces) {
        this.allowedNamespaces = allowedNamespaces == null ? new ArrayList<>() : new ArrayList<>(allowedNamespaces);
    }

    public List<String> getAllowedNames() {
        return List.copyOf(allowedNames);
    }

    public void setAllowedNames(List<String> allowedNames) {
        this.allowedNames = allowedNames == null ? new ArrayList<>() : new ArrayList<>(allowedNames);
    }

    public List<String> getAllowedKeys() {
        return List.copyOf(allowedKeys);
    }

    public void setAllowedKeys(List<String> allowedKeys) {
        this.allowedKeys = allowedKeys == null ? new ArrayList<>() : new ArrayList<>(allowedKeys);
    }

    public enum Backend {
        VALIDATION_ONLY,
        KUBERNETES,
        EXTERNAL_SECRETS
    }
}
