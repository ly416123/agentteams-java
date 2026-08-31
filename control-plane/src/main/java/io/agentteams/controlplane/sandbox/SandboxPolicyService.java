package io.agentteams.controlplane.sandbox;

import io.agentteams.application.api.ExecutionPlacement;
import io.agentteams.application.api.SandboxPolicy;
import io.agentteams.application.api.SandboxProfile;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Resolves the effective sandbox policy from platform to task scope. */
public final class SandboxPolicyService {

    private final Set<String> supportedProviders;

    public SandboxPolicyService(Set<String> supportedProviders) {
        Objects.requireNonNull(supportedProviders, "supportedProviders must not be null");
        if (supportedProviders.isEmpty()) {
            throw new IllegalArgumentException("supportedProviders must not be empty");
        }
        this.supportedProviders = supportedProviders.stream()
                .map(provider -> {
                    Objects.requireNonNull(provider, "supported provider must not be null");
                    if (provider.isBlank()) {
                        throw new IllegalArgumentException("supported provider must not be blank");
                    }
                    return provider.trim();
                })
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public SandboxPolicyService() {
        this(Set.of("platform", "kubernetes", "private"));
    }

    /** Resolves layers in ascending scope order: platform, organization, tenant, project/team, task. */
    public SandboxPolicy resolve(List<SandboxPolicy> layers) {
        Objects.requireNonNull(layers, "layers must not be null");
        if (layers.isEmpty()) {
            return SandboxPolicy.defaults();
        }

        SandboxProfile profile = SandboxProfile.NONE;
        String provider = null;
        ExecutionPlacement placement = ExecutionPlacement.PLATFORM_SHARED;
        String connectorId = null;
        int cpu = Integer.MAX_VALUE;
        int memory = Integer.MAX_VALUE;
        int storage = Integer.MAX_VALUE;
        Duration ttl = SandboxPolicy.MAX_TTL;
        SandboxPolicy.NetworkPolicy network = SandboxPolicy.NetworkPolicy.OPEN;
        Set<String> allowedMcp = null;
        Set<String> allowedDomains = null;
        boolean allowSecrets = true;

        for (SandboxPolicy layer : layers) {
            Objects.requireNonNull(layer, "policy layer must not be null");
            validateProvider(layer.provider());
            profile = stricterProfile(profile, layer.profile());
            provider = mergeProvider(provider, layer.provider());
            placement = stricterPlacement(placement, layer.executionPlacement());
            connectorId = mergeConnector(connectorId, layer.connectorId());
            cpu = Math.min(cpu, layer.cpuMillicores());
            memory = Math.min(memory, layer.memoryMiB());
            storage = Math.min(storage, layer.ephemeralStorageMiB());
            ttl = ttl.compareTo(layer.ttl()) <= 0 ? ttl : layer.ttl();
            network = stricterNetwork(network, layer.networkPolicy());
            allowedMcp = intersect(allowedMcp, layer.allowedMcp());
            allowedDomains = intersect(allowedDomains, layer.allowedDomains());
            allowSecrets &= layer.allowSecretReferences();
        }

        if (provider == null) {
            provider = "platform";
        }
        if (allowedMcp == null) {
            allowedMcp = Set.of();
        }
        if (allowedDomains == null) {
            allowedDomains = Set.of();
        }
        return new SandboxPolicy(profile, provider, placement, cpu, memory, storage, ttl, network,
                allowedMcp, allowedDomains, allowSecrets, connectorId);
    }

    public SandboxPolicy resolve(SandboxPolicy... layers) {
        Objects.requireNonNull(layers, "layers must not be null");
        return resolve(List.of(layers));
    }

    private void validateProvider(String provider) {
        if (!supportedProviders.contains(provider)) {
            throw new IllegalArgumentException("unsupported sandbox provider: " + provider);
        }
    }

    private static String mergeProvider(String current, String next) {
        if (current == null || "platform".equals(current)) {
            return next;
        }
        if ("platform".equals(next) || current.equals(next)) {
            return current;
        }
        throw new IllegalArgumentException("sandbox provider cannot change across policy layers");
    }

    private static String mergeConnector(String current, String next) {
        if (current == null) {
            return next;
        }
        if (next == null || current.equals(next)) {
            return current;
        }
        throw new IllegalArgumentException("connectorId cannot change across policy layers");
    }

    private static SandboxProfile stricterProfile(SandboxProfile left, SandboxProfile right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private static ExecutionPlacement stricterPlacement(ExecutionPlacement left, ExecutionPlacement right) {
        return placementRank(left) >= placementRank(right) ? left : right;
    }

    private static int placementRank(ExecutionPlacement placement) {
        return switch (placement) {
            case PLATFORM_SHARED -> 0;
            case CUSTOMER_CONNECTOR -> 1;
            case PRIVATE_DEPLOYMENT -> 2;
        };
    }

    private static SandboxPolicy.NetworkPolicy stricterNetwork(SandboxPolicy.NetworkPolicy left,
            SandboxPolicy.NetworkPolicy right) {
        return networkRank(left) <= networkRank(right) ? left : right;
    }

    private static int networkRank(SandboxPolicy.NetworkPolicy policy) {
        return switch (policy) {
            case DENY_ALL -> 0;
            case RESTRICTED -> 1;
            case OPEN -> 2;
        };
    }

    private static Set<String> intersect(Set<String> current, Set<String> next) {
        if (current == null) {
            return Set.copyOf(next);
        }
        Set<String> result = new HashSet<>(current);
        result.retainAll(next);
        return Set.copyOf(result);
    }
}
