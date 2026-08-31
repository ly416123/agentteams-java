package io.agentteams.application.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SandboxPolicyTest {

    @Test
    void exposesExecutionPlacementsAndMcpConnectivityModes() {
        assertEquals(Set.of("PLATFORM_SHARED", "CUSTOMER_CONNECTOR", "PRIVATE_DEPLOYMENT"),
                enumNames(ExecutionPlacement.values()));
        assertEquals(Set.of("PLATFORM_PUBLIC", "CUSTOMER_CONNECTOR", "PRIVATE_DEPLOYMENT"),
                enumNames(McpConnectivityMode.values()));
    }

    @Test
    void keepsPolicyFieldsAndCopiesCollectionsDefensively() {
        Set<String> allowedMcp = new HashSet<>(Set.of("github", "linear"));
        Set<String> allowedDomains = new HashSet<>(Set.of("api.github.com"));
        SandboxPolicy policy = new SandboxPolicy(
                SandboxProfile.ISOLATED,
                " kubernetes ",
                ExecutionPlacement.CUSTOMER_CONNECTOR,
                500,
                512,
                1024,
                Duration.ofMinutes(30),
                SandboxPolicy.NetworkPolicy.RESTRICTED,
                allowedMcp,
                allowedDomains,
                false,
                " connector-1 ");

        allowedMcp.add("slack");
        allowedDomains.add("evil.example");

        assertEquals(SandboxProfile.ISOLATED, policy.profile());
        assertEquals("kubernetes", policy.provider());
        assertEquals(ExecutionPlacement.CUSTOMER_CONNECTOR, policy.executionPlacement());
        assertEquals(500, policy.cpuMillicores());
        assertEquals(512, policy.memoryMiB());
        assertEquals(1024, policy.ephemeralStorageMiB());
        assertEquals(Duration.ofMinutes(30), policy.ttl());
        assertEquals(SandboxPolicy.NetworkPolicy.RESTRICTED, policy.networkPolicy());
        assertEquals(Set.of("github", "linear"), policy.allowedMcp());
        assertEquals(Set.of("api.github.com"), policy.allowedDomains());
        assertFalse(policy.allowSecretReferences());
        assertEquals("connector-1", policy.connectorId());
        assertThrows(UnsupportedOperationException.class, () -> policy.allowedMcp().add("slack"));
        assertThrows(UnsupportedOperationException.class, () -> policy.allowedDomains().clear());
    }

    @Test
    void defaultsToTrustedPlatformSharedPolicy() {
        SandboxPolicy policy = SandboxPolicy.defaults();

        assertEquals(SandboxProfile.NONE, policy.profile());
        assertEquals(ExecutionPlacement.PLATFORM_SHARED, policy.executionPlacement());
        assertEquals("platform", policy.provider());
        assertTrue(policy.allowedMcp().isEmpty());
        assertTrue(policy.allowedDomains().isEmpty());
        assertFalse(policy.allowSecretReferences());
    }

    @Test
    void rejectsMissingProviderAndInvalidResourcesOrTtl() {
        assertThrows(NullPointerException.class, () -> policy(null, 500, 512, 1024, Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class, () -> policy(" ", 500, 512, 1024, Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class, () -> policy("kubernetes", 0, 512, 1024,
                Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class, () -> policy("kubernetes", 500, -1, 1024,
                Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class, () -> policy("kubernetes", 500, 512, 0,
                Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class, () -> policy("kubernetes", 500, 512, 1024,
                Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> policy("kubernetes", 500, 512, 1024,
                Duration.ofHours(25)));
        assertThrows(IllegalArgumentException.class, () -> policy("kubernetes",
                SandboxPolicy.MAX_CPU_MILLICORES + 1, 512, 1024, Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class, () -> policy("kubernetes", 500,
                SandboxPolicy.MAX_MEMORY_MIB + 1, 1024, Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class, () -> policy("kubernetes", 500, 512,
                SandboxPolicy.MAX_EPHEMERAL_STORAGE_MIB + 1, Duration.ofMinutes(5)));
    }

    @Test
    void requiresConnectorIdForCustomerConnectorPlacement() {
        assertThrows(IllegalArgumentException.class, () -> new SandboxPolicy(
                SandboxProfile.ISOLATED, "kubernetes", ExecutionPlacement.CUSTOMER_CONNECTOR,
                500, 512, 1024, Duration.ofMinutes(30), SandboxPolicy.NetworkPolicy.RESTRICTED,
                Set.of(), Set.of(), false, null));
        assertThrows(IllegalArgumentException.class, () -> new SandboxPolicy(
                SandboxProfile.ISOLATED, "kubernetes", ExecutionPlacement.CUSTOMER_CONNECTOR,
                500, 512, 1024, Duration.ofMinutes(30), SandboxPolicy.NetworkPolicy.RESTRICTED,
                Set.of(), Set.of(), false, " "));
    }

    @Test
    void rejectsInconsistentHardenedPolicies() {
        assertThrows(IllegalArgumentException.class, () -> new SandboxPolicy(
                SandboxProfile.HARDENED, "kata", ExecutionPlacement.PRIVATE_DEPLOYMENT,
                SandboxPolicy.ISOLATED_MIN_CPU_MILLICORES - 1,
                SandboxPolicy.ISOLATED_MIN_MEMORY_MIB,
                SandboxPolicy.ISOLATED_MIN_EPHEMERAL_STORAGE_MIB,
                Duration.ofMinutes(30), SandboxPolicy.NetworkPolicy.RESTRICTED,
                Set.of(), Set.of(), false, null));
        assertThrows(IllegalArgumentException.class, () -> new SandboxPolicy(
                SandboxProfile.HARDENED, "kata", ExecutionPlacement.PRIVATE_DEPLOYMENT,
                SandboxPolicy.ISOLATED_MIN_CPU_MILLICORES,
                SandboxPolicy.ISOLATED_MIN_MEMORY_MIB - 1,
                SandboxPolicy.ISOLATED_MIN_EPHEMERAL_STORAGE_MIB,
                Duration.ofMinutes(30), SandboxPolicy.NetworkPolicy.RESTRICTED,
                Set.of(), Set.of(), false, null));
        assertThrows(IllegalArgumentException.class, () -> new SandboxPolicy(
                SandboxProfile.HARDENED, "kata", ExecutionPlacement.PRIVATE_DEPLOYMENT,
                SandboxPolicy.ISOLATED_MIN_CPU_MILLICORES,
                SandboxPolicy.ISOLATED_MIN_MEMORY_MIB,
                SandboxPolicy.ISOLATED_MIN_EPHEMERAL_STORAGE_MIB - 1,
                Duration.ofMinutes(30), SandboxPolicy.NetworkPolicy.RESTRICTED,
                Set.of(), Set.of(), false, null));
        assertThrows(IllegalArgumentException.class, () -> new SandboxPolicy(
                SandboxProfile.HARDENED, "kata", ExecutionPlacement.PRIVATE_DEPLOYMENT,
                500, 512, 1024, Duration.ofMinutes(30), SandboxPolicy.NetworkPolicy.OPEN,
                Set.of(), Set.of(), false, null));
        assertThrows(IllegalArgumentException.class, () -> new SandboxPolicy(
                SandboxProfile.HARDENED, "kata", ExecutionPlacement.PRIVATE_DEPLOYMENT,
                500, 512, 1024, SandboxPolicy.MAX_TTL.plusNanos(1),
                SandboxPolicy.NetworkPolicy.RESTRICTED, Set.of(), Set.of(), false, null));
    }

    private static SandboxPolicy policy(String provider, int cpuMillicores, int memoryMiB,
            int ephemeralStorageMiB, Duration ttl) {
        return new SandboxPolicy(SandboxProfile.ISOLATED, provider, ExecutionPlacement.PLATFORM_SHARED,
                cpuMillicores, memoryMiB, ephemeralStorageMiB, ttl, SandboxPolicy.NetworkPolicy.RESTRICTED,
                Set.of(), Set.of(), false, null);
    }

    private static Set<String> enumNames(Enum<?>[] values) {
        return java.util.Arrays.stream(values).map(Enum::name).collect(java.util.stream.Collectors.toSet());
    }
}
