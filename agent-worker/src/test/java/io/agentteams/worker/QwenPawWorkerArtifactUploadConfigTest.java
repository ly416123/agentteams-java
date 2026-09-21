package io.agentteams.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QwenPawWorkerArtifactUploadConfigTest {

    @Test
    void defaultsToDisabledArtifactUploads() {
        QwenPawWorker.WorkerConfiguration configuration =
                QwenPawWorker.WorkerConfiguration.from(Map.of("AGENTTEAMS_AGENT_ID", "worker-1"));

        assertThat(configuration.artifactUploadEnabled()).isFalse();
        assertThat(QwenPawWorker.artifactUploadPort(configuration, null, Clock.systemUTC())).isNull();
    }

    @Test
    void enablesArtifactUploadsFromEnvironment() {
        QwenPawWorker.WorkerConfiguration configuration = QwenPawWorker.WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", "worker-1",
                "AGENTTEAMS_ARTIFACT_UPLOAD_ENABLED", "true",
                "AGENTTEAMS_GATEWAY_AUTH_TOKEN", "worker-gateway-token",
                "AGENTTEAMS_ARTIFACT_UPLOAD_TIMEOUT_SECONDS", "7"));

        assertThat(configuration.artifactUploadEnabled()).isTrue();
        assertThat(configuration.gatewayAuthToken()).isEqualTo("worker-gateway-token");
        assertThat(configuration.artifactUploadTimeout()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void usesTheDefaultUploadTimeoutWhenUnset() {
        QwenPawWorker.WorkerConfiguration configuration = QwenPawWorker.WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", "worker-1",
                "AGENTTEAMS_ARTIFACT_UPLOAD_ENABLED", "true",
                "AGENTTEAMS_GATEWAY_AUTH_TOKEN", "worker-gateway-token"));

        assertThat(configuration.artifactUploadEnabled()).isTrue();
        assertThat(configuration.artifactUploadTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void rejectsEnabledArtifactUploadsWithoutGatewayCredential() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                QwenPawWorker.WorkerConfiguration.from(Map.of(
                        "AGENTTEAMS_AGENT_ID", "worker-1",
                        "AGENTTEAMS_ARTIFACT_UPLOAD_ENABLED", "true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AGENTTEAMS_GATEWAY_AUTH_TOKEN");
    }
}
