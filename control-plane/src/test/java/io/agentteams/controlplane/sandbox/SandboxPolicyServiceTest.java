package io.agentteams.controlplane.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.application.api.ExecutionPlacement;
import io.agentteams.application.api.SandboxPolicy;
import io.agentteams.application.api.SandboxProfile;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SandboxPolicyServiceTest {

    private final SandboxPolicyService service = new SandboxPolicyService();

    @Test
    void defaultsToTrustedPlatformSharedPolicy() {
        SandboxPolicy resolved = service.resolve(List.of());

        assertThat(resolved.profile()).isEqualTo(SandboxProfile.NONE);
        assertThat(resolved.executionPlacement()).isEqualTo(ExecutionPlacement.PLATFORM_SHARED);
        assertThat(resolved.provider()).isEqualTo("platform");
    }

    @Test
    void mergesLayersInOrderAndOnlyTightensSecurity() {
        SandboxPolicy organization = policy(SandboxProfile.ISOLATED, "kubernetes",
                ExecutionPlacement.PLATFORM_SHARED, 2_000, 2_048, 4_096, Duration.ofHours(2),
                SandboxPolicy.NetworkPolicy.RESTRICTED, Set.of("github", "linear"), Set.of("api.github.com"),
                false, null);
        SandboxPolicy tenant = policy(SandboxProfile.ISOLATED, "kubernetes",
                ExecutionPlacement.CUSTOMER_CONNECTOR, 1_000, 1_024, 2_048, Duration.ofHours(1),
                SandboxPolicy.NetworkPolicy.DENY_ALL, Set.of("github"), Set.of("api.github.com"), true, "connector-1");
        SandboxPolicy project = policy(SandboxProfile.ISOLATED, "kubernetes",
                ExecutionPlacement.CUSTOMER_CONNECTOR, 1_500, 1_536, 3_072, Duration.ofMinutes(45),
                SandboxPolicy.NetworkPolicy.DENY_ALL, Set.of("github"), Set.of("api.github.com"), false, "connector-1");
        SandboxPolicy team = policy(SandboxProfile.ISOLATED, "kubernetes",
                ExecutionPlacement.CUSTOMER_CONNECTOR, 1_200, 1_280, 2_560, Duration.ofMinutes(40),
                SandboxPolicy.NetworkPolicy.DENY_ALL, Set.of("github"), Set.of("api.github.com"), false, "connector-1");
        SandboxPolicy task = policy(SandboxProfile.NONE, "platform", ExecutionPlacement.PLATFORM_SHARED,
                8_000, 8_000, 8_000, Duration.ofHours(4), SandboxPolicy.NetworkPolicy.OPEN,
                Set.of("github"), Set.of("api.github.com"), true, null);

        SandboxPolicy resolved = service.resolve(List.of(organization, tenant, project, team, task));

        assertThat(resolved.profile()).isEqualTo(SandboxProfile.ISOLATED);
        assertThat(resolved.provider()).isEqualTo("kubernetes");
        assertThat(resolved.executionPlacement()).isEqualTo(ExecutionPlacement.CUSTOMER_CONNECTOR);
        assertThat(resolved.connectorId()).isEqualTo("connector-1");
        assertThat(resolved.cpuMillicores()).isEqualTo(1_000);
        assertThat(resolved.memoryMiB()).isEqualTo(1_024);
        assertThat(resolved.ephemeralStorageMiB()).isEqualTo(2_048);
        assertThat(resolved.ttl()).isEqualTo(Duration.ofMinutes(40));
        assertThat(resolved.networkPolicy()).isEqualTo(SandboxPolicy.NetworkPolicy.DENY_ALL);
        assertThat(resolved.allowedMcp()).containsExactly("github");
        assertThat(resolved.allowedDomains()).containsExactly("api.github.com");
        assertThat(resolved.allowSecretReferences()).isFalse();
    }

    @Test
    void rejectsConflictingConnectorAndUnsupportedProvider() {
        SandboxPolicy first = policy(SandboxProfile.ISOLATED, "kubernetes",
                ExecutionPlacement.CUSTOMER_CONNECTOR, 500, 512, 1_024, Duration.ofMinutes(30),
                SandboxPolicy.NetworkPolicy.RESTRICTED, Set.of(), Set.of(), false, "connector-1");
        SandboxPolicy second = policy(SandboxProfile.ISOLATED, "kubernetes",
                ExecutionPlacement.CUSTOMER_CONNECTOR, 500, 512, 1_024, Duration.ofMinutes(30),
                SandboxPolicy.NetworkPolicy.RESTRICTED, Set.of(), Set.of(), false, "connector-2");

        assertThatThrownBy(() -> service.resolve(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connector");
        assertThatThrownBy(() -> service.resolve(List.of(policy(SandboxProfile.ISOLATED, "unknown",
                ExecutionPlacement.PLATFORM_SHARED, 500, 512, 1_024, Duration.ofMinutes(30),
                SandboxPolicy.NetworkPolicy.RESTRICTED, Set.of(), Set.of(), false, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider");
    }

    @Test
    void rejectsCustomerConnectorWithoutConnectorId() {
        assertThatThrownBy(() -> new SandboxPolicy(SandboxProfile.ISOLATED, "kubernetes",
                ExecutionPlacement.CUSTOMER_CONNECTOR, 500, 512, 1_024, Duration.ofMinutes(30),
                SandboxPolicy.NetworkPolicy.RESTRICTED, Set.of(), Set.of(), false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectorId");
    }

    private static SandboxPolicy policy(SandboxProfile profile, String provider, ExecutionPlacement placement,
            int cpu, int memory, int storage, Duration ttl, SandboxPolicy.NetworkPolicy network,
            Set<String> mcp, Set<String> domains, boolean secrets, String connector) {
        return new SandboxPolicy(profile, provider, placement, cpu, memory, storage, ttl, network,
                mcp, domains, secrets, connector);
    }
}
