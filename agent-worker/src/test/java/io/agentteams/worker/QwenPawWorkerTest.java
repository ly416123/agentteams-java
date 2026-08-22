package io.agentteams.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import io.agentteams.contracts.v1.AgentHello;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QwenPawWorkerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void readsStableDefaultsAndBuildsCanonicalHello() {
        QwenPawWorker.WorkerConfiguration configuration = QwenPawWorker.WorkerConfiguration.from(
                Map.of("AGENTTEAMS_AGENT_ID", "00000000-0000-0000-0000-000000000001"));

        AgentHello hello = QwenPawWorker.hello(configuration, CLOCK);

        assertThat(configuration.gatewayHost()).isEqualTo("agentteams-agentteams-java-gateway");
        assertThat(configuration.gatewayPort()).isEqualTo(9090);
        assertThat(configuration.qwenPawEndpoint()).isEqualTo("http://qwenpaw:8088");
        assertThat(hello.getMetadata().getAgentId()).isEqualTo(configuration.agentId());
        assertThat(hello.getRuntimeName()).isEqualTo("qwenpaw");
        assertThat(hello.getProtocolVersion().getMajor()).isEqualTo(2);
        assertThat(hello.getCapabilitiesMap()).containsEntry("http-sse", "v1");
        assertThat(hello.getMetadata().getOccurredAt()).isEqualTo(Timestamp.newBuilder()
                .setSeconds(Instant.parse("2026-08-19T00:00:00Z").getEpochSecond()).build());
    }

    @Test
    void requiresAgentTeamsIdentity() {
        assertThatThrownBy(() -> QwenPawWorker.WorkerConfiguration.from(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AGENTTEAMS_AGENT_ID must be set");
    }

    @Test
    void validatesPositiveNumericConfiguration() {
        assertThatThrownBy(() -> QwenPawWorker.WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", "agent-a",
                "AGENTTEAMS_MAX_CONCURRENT_TASKS", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AGENTTEAMS_MAX_CONCURRENT_TASKS must be positive");
    }

    @Test
    void requiresClientCertificateMaterialWhenGatewayTlsIsEnabled() {
        assertThatThrownBy(() -> QwenPawWorker.WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", "agent-a",
                "AGENTTEAMS_GATEWAY_TLS_ENABLED", "true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AGENTTEAMS_GATEWAY_TLS_CA_CERT_PATH");
    }
}
