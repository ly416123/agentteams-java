package io.agentteams.application.api;

import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, provider-neutral policy describing the limits of one sandbox.
 *
 * <p>Resource values use millicores and MiB. The policy is deliberately a
 * value object: it has no persistence or framework dependencies.</p>
 */
public final class SandboxPolicy {

    public static final int MAX_CPU_MILLICORES = 16_000;
    public static final int MAX_MEMORY_MIB = 65_536;
    public static final int MAX_EPHEMERAL_STORAGE_MIB = 131_072;
    public static final Duration MAX_TTL = Duration.ofHours(24);

    public static final int ISOLATED_MIN_CPU_MILLICORES = 250;
    public static final int ISOLATED_MIN_MEMORY_MIB = 256;
    public static final int ISOLATED_MIN_EPHEMERAL_STORAGE_MIB = 512;

    private final SandboxProfile profile;
    private final String provider;
    private final ExecutionPlacement executionPlacement;
    private final int cpuMillicores;
    private final int memoryMiB;
    private final int ephemeralStorageMiB;
    private final Duration ttl;
    private final NetworkPolicy networkPolicy;
    private final Set<String> allowedMcp;
    private final Set<String> allowedDomains;
    private final boolean allowSecretReferences;
    private final String connectorId;

    public SandboxPolicy(SandboxProfile profile, String provider, ExecutionPlacement executionPlacement,
            int cpuMillicores, int memoryMiB, int ephemeralStorageMiB, Duration ttl,
            NetworkPolicy networkPolicy, Set<String> allowedMcp, Set<String> allowedDomains,
            boolean allowSecretReferences, String connectorId) {
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.provider = required(provider, "provider");
        this.executionPlacement = Objects.requireNonNull(executionPlacement,
                "executionPlacement must not be null");
        this.cpuMillicores = boundedPositive(cpuMillicores, MAX_CPU_MILLICORES, "cpuMillicores");
        this.memoryMiB = boundedPositive(memoryMiB, MAX_MEMORY_MIB, "memoryMiB");
        this.ephemeralStorageMiB = boundedPositive(ephemeralStorageMiB, MAX_EPHEMERAL_STORAGE_MIB,
                "ephemeralStorageMiB");
        this.ttl = boundedTtl(ttl);
        this.networkPolicy = Objects.requireNonNull(networkPolicy, "networkPolicy must not be null");
        this.allowedMcp = immutableNames(allowedMcp, "allowedMcp");
        this.allowedDomains = immutableNames(allowedDomains, "allowedDomains");
        this.allowSecretReferences = allowSecretReferences;
        this.connectorId = optional(connectorId, "connectorId");

        if (executionPlacement == ExecutionPlacement.CUSTOMER_CONNECTOR && this.connectorId == null) {
            throw new IllegalArgumentException("connectorId is required for CUSTOMER_CONNECTOR");
        }
        if (profile == SandboxProfile.HARDENED) {
            rejectHardenedBelowIsolatedBaseline();
        }
    }

    public SandboxPolicy(SandboxProfile profile, String provider, ExecutionPlacement executionPlacement,
            int cpuMillicores, int memoryMiB, int ephemeralStorageMiB, Duration ttl,
            NetworkPolicy networkPolicy, Set<String> allowedMcp, Set<String> allowedDomains,
            boolean allowSecretReferences) {
        this(profile, provider, executionPlacement, cpuMillicores, memoryMiB, ephemeralStorageMiB, ttl,
                networkPolicy, allowedMcp, allowedDomains, allowSecretReferences, null);
    }

    public SandboxPolicy(SandboxProfile profile, String provider, ExecutionPlacement executionPlacement,
            String connectorId, int cpuMillicores, int memoryMiB, int ephemeralStorageMiB, Duration ttl,
            NetworkPolicy networkPolicy, Set<String> allowedMcp, Set<String> allowedDomains,
            boolean allowSecretReferences) {
        this(profile, provider, executionPlacement, cpuMillicores, memoryMiB, ephemeralStorageMiB, ttl,
                networkPolicy, allowedMcp, allowedDomains, allowSecretReferences, connectorId);
    }

    public static SandboxPolicy defaults() {
        return new SandboxPolicy(SandboxProfile.NONE, "platform", ExecutionPlacement.PLATFORM_SHARED,
                ISOLATED_MIN_CPU_MILLICORES, ISOLATED_MIN_MEMORY_MIB,
                ISOLATED_MIN_EPHEMERAL_STORAGE_MIB, Duration.ofMinutes(30), NetworkPolicy.DENY_ALL,
                Set.of(), Set.of(), false, null);
    }

    public SandboxProfile profile() {
        return profile;
    }

    public String provider() {
        return provider;
    }

    public ExecutionPlacement executionPlacement() {
        return executionPlacement;
    }

    public int cpuMillicores() {
        return cpuMillicores;
    }

    public int memoryMiB() {
        return memoryMiB;
    }

    public int ephemeralStorageMiB() {
        return ephemeralStorageMiB;
    }

    public Duration ttl() {
        return ttl;
    }

    public NetworkPolicy networkPolicy() {
        return networkPolicy;
    }

    public Set<String> allowedMcp() {
        return allowedMcp;
    }

    public Set<String> allowedDomains() {
        return allowedDomains;
    }

    public boolean allowSecretReferences() {
        return allowSecretReferences;
    }

    public boolean secretReferencesAllowed() {
        return allowSecretReferences;
    }

    public String connectorId() {
        return connectorId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SandboxPolicy that)) {
            return false;
        }
        return cpuMillicores == that.cpuMillicores
                && memoryMiB == that.memoryMiB
                && ephemeralStorageMiB == that.ephemeralStorageMiB
                && allowSecretReferences == that.allowSecretReferences
                && profile == that.profile
                && provider.equals(that.provider)
                && executionPlacement == that.executionPlacement
                && ttl.equals(that.ttl)
                && networkPolicy == that.networkPolicy
                && allowedMcp.equals(that.allowedMcp)
                && allowedDomains.equals(that.allowedDomains)
                && Objects.equals(connectorId, that.connectorId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profile, provider, executionPlacement, cpuMillicores, memoryMiB,
                ephemeralStorageMiB, ttl, networkPolicy, allowedMcp, allowedDomains,
                allowSecretReferences, connectorId);
    }

    @Override
    public String toString() {
        return "SandboxPolicy[profile=" + profile + ", provider=" + provider
                + ", executionPlacement=" + executionPlacement + ", cpuMillicores=" + cpuMillicores
                + ", memoryMiB=" + memoryMiB + ", ephemeralStorageMiB=" + ephemeralStorageMiB
                + ", ttl=" + ttl + ", networkPolicy=" + networkPolicy + ", allowedMcp=" + allowedMcp
                + ", allowedDomains=" + allowedDomains + ", allowSecretReferences="
                + allowSecretReferences + ", connectorId=" + connectorId + ']';
    }

    private void rejectHardenedBelowIsolatedBaseline() {
        if (cpuMillicores < ISOLATED_MIN_CPU_MILLICORES
                || memoryMiB < ISOLATED_MIN_MEMORY_MIB
                || ephemeralStorageMiB < ISOLATED_MIN_EPHEMERAL_STORAGE_MIB) {
            throw new IllegalArgumentException("HARDENED policy must meet the ISOLATED resource baseline");
        }
        if (networkPolicy == NetworkPolicy.OPEN) {
            throw new IllegalArgumentException("HARDENED policy cannot use OPEN network access");
        }
    }

    private static int boundedPositive(int value, int maximum, String field) {
        if (value <= 0 || value > maximum) {
            throw new IllegalArgumentException(field + " must be greater than zero and no more than " + maximum);
        }
        return value;
    }

    private static Duration boundedTtl(Duration value) {
        Objects.requireNonNull(value, "ttl must not be null");
        if (value.isZero() || value.isNegative() || value.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("ttl must be greater than zero and no more than 24 hours");
        }
        return value;
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String optional(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return required(value, field);
    }

    private static Set<String> immutableNames(Set<String> values, String field) {
        Objects.requireNonNull(values, field + " must not be null");
        Set<String> copy = new HashSet<>();
        for (String value : values) {
            copy.add(required(value, field + " entry"));
        }
        return Set.copyOf(copy);
    }

    /** Network egress posture for the sandbox. */
    public enum NetworkPolicy {
        DENY_ALL,
        RESTRICTED,
        OPEN
    }
}
