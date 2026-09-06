package io.agentteams.controlplane.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

    @Test
    void rejectsScansWhenTheConcurrencyLimitIsFull() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SkillSandboxScannerClient client = request -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("scanner test interrupted", interrupted);
            }
            return new SkillSandboxScannerClient.ScanResult(
                    SkillSandboxScannerClient.Decision.CLEAN, "clean", null);
        };
        ExecutorService scannerExecutor = Executors.newSingleThreadExecutor();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        ConfiguredExternalSkillSandboxScanner scanner = new ConfiguredExternalSkillSandboxScanner(
                client, Duration.ofSeconds(1), scannerExecutor, 1);
        try {
            Future<ExternalSkillSandboxScanner.SandboxScanResult> first = caller.submit(() -> scanner.scan(
                    new ExternalSkillSandboxScanner.SandboxScanRequest("{}", null)));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            ExternalSkillSandboxScanner.SandboxScanResult rejected = scanner.scan(
                    new ExternalSkillSandboxScanner.SandboxScanRequest("{}", null));

            assertThat(rejected.decision()).isEqualTo(ExternalSkillSandboxScanner.Decision.REVIEW_REQUIRED);
            assertThat(rejected.classification()).isEqualTo(ExternalSkillSandboxScanner.SANDBOX_UNAVAILABLE);
            release.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS).decision()).isEqualTo(ExternalSkillSandboxScanner.Decision.CLEAN);
        } finally {
            release.countDown();
            scanner.close();
            caller.shutdownNow();
        }
    }
}
