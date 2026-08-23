package io.agentteams.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfigFailureClassifierTest {
    @Test
    void mapsCommonWorkerFailuresToStableCodes() {
        assertThat(ConfigFailureClassifier.classify("worker timed out while downloading config")).isEqualTo("TIMEOUT");
        assertThat(ConfigFailureClassifier.classify("403 forbidden")).isEqualTo("AUTHORIZATION");
        assertThat(ConfigFailureClassifier.classify("checksum validation failed")).isEqualTo("VALIDATION");
        assertThat(ConfigFailureClassifier.classify("provider unsupported")).isEqualTo("UNSUPPORTED");
        assertThat(ConfigFailureClassifier.classify("unexpected worker failure")).isEqualTo("WORKER_ERROR");
        assertThat(ConfigFailureClassifier.classify(null)).isNull();
    }
}
