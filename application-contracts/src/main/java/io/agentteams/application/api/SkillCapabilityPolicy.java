package io.agentteams.application.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** Capabilities a Skill requests; the effective sandbox policy remains authoritative. */
public record SkillCapabilityPolicy(SandboxProfile profile, int cpuMillicores, int memoryMiB,
        int ephemeralStorageMiB, Duration ttl, Set<String> allowedMcp, Set<String> allowedDomains,
        Set<String> allowedTools, boolean allowSecretReferences, SandboxPolicy.NetworkPolicy networkPolicy) {

    /** Compatibility constructor for manifests created before networkPolicy became explicit. */
    public SkillCapabilityPolicy(SandboxProfile profile, int cpuMillicores, int memoryMiB,
            int ephemeralStorageMiB, Duration ttl, Set<String> allowedMcp, Set<String> allowedDomains,
            boolean allowSecretReferences) {
        this(profile, cpuMillicores, memoryMiB, ephemeralStorageMiB, ttl, allowedMcp, allowedDomains,
                Set.of(), allowSecretReferences, SandboxPolicy.NetworkPolicy.DENY_ALL);
    }

    /** Compatibility constructor for callers that already provide an explicit network policy. */
    public SkillCapabilityPolicy(SandboxProfile profile, int cpuMillicores, int memoryMiB,
            int ephemeralStorageMiB, Duration ttl, Set<String> allowedMcp, Set<String> allowedDomains,
            boolean allowSecretReferences, SandboxPolicy.NetworkPolicy networkPolicy) {
        this(profile, cpuMillicores, memoryMiB, ephemeralStorageMiB, ttl, allowedMcp, allowedDomains,
                Set.of(), allowSecretReferences, networkPolicy);
    }

    public SkillCapabilityPolicy {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(networkPolicy, "networkPolicy");
        allowedMcp = names(allowedMcp, "allowedMcp");
        allowedDomains = names(allowedDomains, "allowedDomains");
        allowedTools = names(allowedTools, "allowedTools");
        if (cpuMillicores <= 0 || cpuMillicores > SandboxPolicy.MAX_CPU_MILLICORES
                || memoryMiB <= 0 || memoryMiB > SandboxPolicy.MAX_MEMORY_MIB
                || ephemeralStorageMiB <= 0 || ephemeralStorageMiB > SandboxPolicy.MAX_EPHEMERAL_STORAGE_MIB) {
            throw new IllegalArgumentException("skill resource requests must be positive and within platform limits");
        }
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(SandboxPolicy.MAX_TTL) > 0) {
            throw new IllegalArgumentException("skill ttl must be positive and no more than 24 hours");
        }
    }

    public void requireAllowedBy(SandboxPolicy effective) {
        Objects.requireNonNull(effective, "effective");
        if (profile.ordinal() > effective.profile().ordinal()
                || cpuMillicores > effective.cpuMillicores()
                || memoryMiB > effective.memoryMiB()
                || ephemeralStorageMiB > effective.ephemeralStorageMiB()
                || ttl.compareTo(effective.ttl()) > 0
                || networkPolicy.ordinal() > effective.networkPolicy().ordinal()
                || !effective.allowedMcp().containsAll(allowedMcp)
                || !effective.allowedDomains().containsAll(allowedDomains)
                || (allowSecretReferences && !effective.allowSecretReferences())) {
            throw new IllegalArgumentException("skill capabilities exceed the effective sandbox policy");
        }
    }

    private static Set<String> names(Set<String> values, String field) {
        Objects.requireNonNull(values, field);
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(field + " must contain non-blank values");
        }
        return Set.copyOf(values);
    }
}
