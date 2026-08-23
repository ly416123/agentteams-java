package io.agentteams.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.grpc.ManagedChannel;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManagerQuotaPortFactoryTest {
    @Test
    void keepsLegacyManagerUnscopedWhenRemoteQuotaIsDisabled() {
        QuotaPort port = ManagerQuotaPortFactory.from(Map.of(), mock(ManagedChannel.class), "manager-a");

        assertThat(port).isNotInstanceOf(GrpcQuotaPort.class);
    }

    @Test
    void validatesRemoteQuotaTimeout() {
        assertThatThrownBy(() -> ManagerQuotaPortFactory.from(Map.of(
                "AGENTTEAMS_QUOTA_REMOTE_ENABLED", "true",
                "AGENTTEAMS_QUOTA_TIMEOUT_SECONDS", "0"),
                mock(ManagedChannel.class), "manager-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AGENTTEAMS_QUOTA_TIMEOUT_SECONDS must be a positive integer");
    }

    @Test
    void createsGrpcPortWhenExplicitlyEnabled() {
        QuotaPort port = ManagerQuotaPortFactory.from(Map.of(
                "AGENTTEAMS_QUOTA_REMOTE_ENABLED", "true"),
                mock(ManagedChannel.class), "manager-a");

        assertThat(port).isInstanceOf(GrpcQuotaPort.class);
    }
}
