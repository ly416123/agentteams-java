package io.agentteams.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import io.agentteams.application.api.ExecutionRuntime;
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
        assertThat(configuration.runtime()).isEqualTo(ExecutionRuntime.QWENPAW);
        assertThat(configuration.modelProvider()).isEqualTo("qwenpaw");
        assertThat(configuration.model()).isEqualTo("unknown");
        assertThat(configuration.modelMaxTokens()).isEqualTo(1024);
        assertThat(configuration.modelCallMaxConcurrent()).isEqualTo(1);
        assertThat(hello.getMetadata().getAgentId()).isEqualTo(configuration.agentId());
        assertThat(hello.getRuntimeName()).isEqualTo("qwenpaw");
        assertThat(hello.getProtocolVersion().getMajor()).isEqualTo(2);
        assertThat(hello.getCapabilitiesMap()).containsEntry("http-sse", "v1");
        assertThat(hello.getMetadata().getOccurredAt()).isEqualTo(Timestamp.newBuilder()
                .setSeconds(Instant.parse("2026-08-19T00:00:00Z").getEpochSecond()).build());
    }

    @Test
    void advertisesWorkerVersionFactsInHelloWhenInjectedByOperator() {
        QwenPawWorker.WorkerConfiguration configuration = QwenPawWorker.WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", "00000000-0000-0000-0000-000000000001",
                "AGENTTEAMS_SPEC_DIGEST", "sha256:worker-v2",
                "AGENTTEAMS_CONFIG_REVISION", "config-17",
                "AGENTTEAMS_SECRET_GENERATION", "secret-9"));

        AgentHello hello = QwenPawWorker.hello(configuration, CLOCK);

        assertThat(hello.getSpecDigest()).isEqualTo("sha256:worker-v2");
        assertThat(hello.getConfigRevision()).isEqualTo("config-17");
        assertThat(hello.getSecretGeneration()).isEqualTo("secret-9");
    }

    @Test
    void parsesAgentScopeRuntimeTypeWithoutChangingExecutionPath() {
        QwenPawWorker.WorkerConfiguration configuration = QwenPawWorker.WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", "agent-a",
                "AGENTTEAMS_RUNTIME", "AGENTSCOPE"));

        assertThat(configuration.runtime()).isEqualTo(ExecutionRuntime.AGENTSCOPE);
    }

    @Test
    void parsesExplicitQwenPawRuntimeType() {
        QwenPawWorker.WorkerConfiguration configuration = QwenPawWorker.WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", "agent-a",
                "AGENTTEAMS_RUNTIME", "QWENPAW"));

        assertThat(configuration.runtime()).isEqualTo(ExecutionRuntime.QWENPAW);
    }

    @Test
    void fallsBackToQwenPawForBlankRuntimeConfiguration() {
        QwenPawWorker.WorkerConfiguration configuration = QwenPawWorker.WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", "agent-a",
                "AGENTTEAMS_RUNTIME", "  "));

        assertThat(configuration.runtime()).isEqualTo(ExecutionRuntime.QWENPAW);
    }

    @Test
    void rejectsUnknownRuntimeType() {
        assertThatThrownBy(() -> QwenPawWorker.WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", "agent-a",
                "AGENTTEAMS_RUNTIME", "UNKNOWN")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported execution runtime: UNKNOWN; expected QWENPAW or AGENTSCOPE");
    }

    @Test
    void rejectsExplicitAgentScopeWhenItsRuntimeConfigurationIsMissing() {
        QwenPawWorker.WorkerConfiguration configuration = QwenPawWorker.WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", "agent-a",
                "AGENTTEAMS_RUNTIME", "AGENTSCOPE"));

        assertThatThrownBy(() -> new QwenPawWorker(configuration, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AGENTSCOPE runtime requires configured Harness and model");
    }

    @Test
    void readsOptionalWorkerProjectScopeIntoRuntimeConfiguration() {
        QwenPawWorker.WorkerConfiguration configuration = QwenPawWorker.WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", "agent-a",
                "AGENTTEAMS_SCOPE_TENANT", "tenant-a",
                "AGENTTEAMS_SCOPE_PROJECT", "project-a"));

        assertThat(configuration.tenantId()).isEqualTo("tenant-a");
        assertThat(configuration.projectId()).isEqualTo("project-a");
        assertThat(configuration.runtimeConfiguration())
                .containsEntry("tenant_id", "tenant-a")
                .containsEntry("project_id", "project-a");
    }

    @Test
    void rejectsPartialWorkerProjectScope() {
        assertThatThrownBy(() -> QwenPawWorker.WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", "agent-a",
                "AGENTTEAMS_TENANT_ID", "tenant-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenant and project scope must be supplied together");
    }

    @Test
    void enablesRemoteQuotaOnlyWithAnExplicitScopedConfiguration() {
        QwenPawWorker.WorkerConfiguration configuration = QwenPawWorker.WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", "agent-a",
                "AGENTTEAMS_SCOPE_TENANT", "tenant-a",
                "AGENTTEAMS_SCOPE_PROJECT", "project-a",
                "AGENTTEAMS_QUOTA_REMOTE_ENABLED", "true",
                "AGENTTEAMS_QUOTA_TIMEOUT_SECONDS", "7"));

        assertThat(configuration.quotaRemoteEnabled()).isTrue();
        assertThat(configuration.quotaTimeout()).isEqualTo(java.time.Duration.ofSeconds(7));
    }

    @Test
    void rejectsRemoteQuotaWithoutAProjectScope() {
        assertThatThrownBy(() -> QwenPawWorker.WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", "agent-a",
                "AGENTTEAMS_QUOTA_REMOTE_ENABLED", "true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenant and project scope must be supplied when remote quota is enabled");
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
