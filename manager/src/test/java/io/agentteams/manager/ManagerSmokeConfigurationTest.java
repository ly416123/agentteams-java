package io.agentteams.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ManagerSmokeConfigurationTest {
    @Test
    void remoteQuotaRequiresScopeAndManagerIdentity() {
        assertThatThrownBy(() -> ManagerSmokeConfiguration.from(Map.of(
                "AGENTTEAMS_QUOTA_REMOTE_ENABLED", "true",
                "AGENTTEAMS_SCOPE_TENANT", "tenant-a",
                "AGENTTEAMS_SCOPE_PROJECT", "project-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AGENTTEAMS_MANAGER_ID is required when remote quota is enabled");
    }

    @Test
    void remoteQuotaRequiresCompleteProjectScope() {
        assertThatThrownBy(() -> ManagerSmokeConfiguration.from(Map.of(
                "AGENTTEAMS_QUOTA_REMOTE_ENABLED", "true",
                "AGENTTEAMS_MANAGER_ID", "manager-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenant and project scope must be supplied when remote quota is enabled");
    }

    @Test
    void disabledRemoteQuotaKeepsCompatibilityDefaults() {
        ManagerSmokeConfiguration configuration = ManagerSmokeConfiguration.from(Map.of());

        assertThat(configuration.remoteQuotaEnabled()).isFalse();
        assertThat(configuration.managerId()).isEqualTo("manager-smoke");
        assertThat(configuration.gatewayHost()).isEqualTo("agentteams-agentteams-java-gateway");
        assertThat(configuration.gatewayPort()).isEqualTo(9090);
    }

    @Test
    void remoteQuotaLoadsGatewayAndScopeConfiguration() {
        ManagerSmokeConfiguration configuration = ManagerSmokeConfiguration.from(Map.of(
                "AGENTTEAMS_QUOTA_REMOTE_ENABLED", "true",
                "AGENTTEAMS_MANAGER_ID", "manager-a",
                "AGENTTEAMS_GATEWAY_HOST", "gateway.local",
                "AGENTTEAMS_GATEWAY_PORT", "19090",
                "AGENTTEAMS_SCOPE_TENANT", "tenant-a",
                "AGENTTEAMS_SCOPE_PROJECT", "project-a"));

        assertThat(configuration.remoteQuotaEnabled()).isTrue();
        assertThat(configuration.managerId()).isEqualTo("manager-a");
        assertThat(configuration.gatewayHost()).isEqualTo("gateway.local");
        assertThat(configuration.gatewayPort()).isEqualTo(19090);
        assertThat(configuration.tenantId()).isEqualTo("tenant-a");
        assertThat(configuration.projectId()).isEqualTo("project-a");
    }

    @Test
    void remoteQuotaRejectsInvalidGatewayPort() {
        assertThatThrownBy(() -> ManagerSmokeConfiguration.from(Map.of(
                "AGENTTEAMS_QUOTA_REMOTE_ENABLED", "true",
                "AGENTTEAMS_MANAGER_ID", "manager-a",
                "AGENTTEAMS_SCOPE_TENANT", "tenant-a",
                "AGENTTEAMS_SCOPE_PROJECT", "project-a",
                "AGENTTEAMS_GATEWAY_PORT", "70000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AGENTTEAMS_GATEWAY_PORT must be between 1 and 65535");
    }
}
