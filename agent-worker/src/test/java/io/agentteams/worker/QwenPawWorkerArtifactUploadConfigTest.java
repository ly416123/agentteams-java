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
                "AGENTTEAMS_ARTIFACT_UPLOAD_TIMEOUT_SECONDS", "7"));

        assertThat(configuration.artifactUploadEnabled()).isTrue();
        assertThat(configuration.artifactUploadTimeout()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void usesTheDefaultUploadTimeoutWhenUnset() {
        QwenPawWorker.WorkerConfiguration configuration = QwenPawWorker.WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", "worker-1",
                "AGENTTEAMS_ARTIFACT_UPLOAD_ENABLED", "true"));

        assertThat(configuration.artifactUploadEnabled()).isTrue();
        assertThat(configuration.artifactUploadTimeout()).isEqualTo(Duration.ofSeconds(5));
    }
}
