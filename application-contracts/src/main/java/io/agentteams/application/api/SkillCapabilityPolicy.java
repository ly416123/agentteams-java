package io.agentteams.application.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** Capabilities a Skill requests; the effective sandbox policy remains authoritative. */
public record SkillCapabilityPolicy(SandboxProfile profile, int cpuMillicores, int memoryMiB,
        int ephemeralStorageMiB, Duration ttl, Set<String> allowedMcp, Set<String> allowedDomains,
        boolean allowSecretReferences) {

    public SkillCapabilityPolicy {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(ttl, "ttl");
        allowedMcp = names(allowedMcp, "allowedMcp");
        allowedDomains = names(allowedDomains, "allowedDomains");
        if (cpuMillicores <= 0 || memoryMiB <= 0 || ephemeralStorageMiB <= 0) {
            throw new IllegalArgumentException("skill resource requests must be positive");
        }
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("skill ttl must be positive");
    }

    public void requireAllowedBy(SandboxPolicy effective) {
        Objects.requireNonNull(effective, "effective");
        if (profile.ordinal() > effective.profile().ordinal()
                || cpuMillicores > effective.cpuMillicores()
                || memoryMiB > effective.memoryMiB()
                || ephemeralStorageMiB > effective.ephemeralStorageMiB()
                || ttl.compareTo(effective.ttl()) > 0
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
