package io.agentteams.controlplane.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ExternalSkillSandboxScannerTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void close() {
        executor.shutdownNow();
    }

    @Test
    void configuredClientMapsCleanResultWithoutExposingDetails() {
        SkillSandboxScannerClient client = request -> new SkillSandboxScannerClient.ScanResult(
                SkillSandboxScannerClient.Decision.CLEAN, "clean-package", "vendor body");
        ConfiguredExternalSkillSandboxScanner scanner = new ConfiguredExternalSkillSandboxScanner(
                client, Duration.ofSeconds(1), executor);

        ExternalSkillSandboxScanner.SandboxScanResult result = scanner.scan(
                new ExternalSkillSandboxScanner.SandboxScanRequest("{}", null));

        assertThat(result.decision()).isEqualTo(ExternalSkillSandboxScanner.Decision.CLEAN);
        assertThat(result.classification()).isEqualTo("clean-package");
        assertThat(result.vendorDetail()).isNull();
    }

    @Test
    void configuredClientMapsTimeoutToStableReviewClassification() {
        SkillSandboxScannerClient client = request -> {
            Thread.sleep(250);
            return new SkillSandboxScannerClient.ScanResult(
                    SkillSandboxScannerClient.Decision.CLEAN, "clean", null);
        };
        ConfiguredExternalSkillSandboxScanner scanner = new ConfiguredExternalSkillSandboxScanner(
                client, Duration.ofMillis(20), executor);

        ExternalSkillSandboxScanner.SandboxScanResult result = scanner.scan(
                new ExternalSkillSandboxScanner.SandboxScanRequest("{}", null));

        assertThat(result.decision()).isEqualTo(ExternalSkillSandboxScanner.Decision.REVIEW_REQUIRED);
        assertThat(result.classification()).isEqualTo(ExternalSkillSandboxScanner.SANDBOX_TIMEOUT);
    }

    @Test
    void configuredClientMapsFailureToStableUnavailableClassification() {
        SkillSandboxScannerClient client = request -> {
            throw new IllegalStateException("secret response body");
        };
        ConfiguredExternalSkillSandboxScanner scanner = new ConfiguredExternalSkillSandboxScanner(
                client, Duration.ofSeconds(1), executor);

        ExternalSkillSandboxScanner.SandboxScanResult result = scanner.scan(
                new ExternalSkillSandboxScanner.SandboxScanRequest("{}", null));

        assertThat(result.decision()).isEqualTo(ExternalSkillSandboxScanner.Decision.REVIEW_REQUIRED);
        assertThat(result.classification()).isEqualTo(ExternalSkillSandboxScanner.SANDBOX_UNAVAILABLE);
        assertThat(result.vendorDetail()).isNull();
    }
}
