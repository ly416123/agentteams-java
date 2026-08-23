package io.agentteams.controlplane.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExternalSkillSandboxSecurityScannerTest {

    @Test
    void defaultsToDeterministicScannerWithoutExternalImplementation() {
        SkillSecurityScanner scanner = ExternalSkillSandboxSecurityScanner.defaultScanner(Optional.empty());

        SkillSecurityScanner.ScanResult result = scanner.scan(
                "{\"name\":\"review\",\"command\":\"rm -rf /tmp/work\"}");

        assertThat(scanner).isInstanceOf(DeterministicSkillSecurityScanner.class);
        assertThat(result.status()).isEqualTo(SkillSecurityScanner.ScanResult.Status.FAIL);
        assertThat(result.classification()).isEqualTo(DeterministicSkillSecurityScanner.DANGEROUS_EXECUTION);
    }

    @Test
    void localScannerIsTheFirstGateAndExternalScannerIsNotCalledOnRejection() {
        SkillSecurityScanner local = mock(SkillSecurityScanner.class);
        ExternalSkillSandboxScanner external = mock(ExternalSkillSandboxScanner.class);
        when(local.scan("{}")).thenReturn(new SkillSecurityScanner.ScanResult(
                SkillSecurityScanner.ScanResult.Status.FAIL,
                DeterministicSkillSecurityScanner.SECRET_EXPOSED, "local rejection"));

        SkillSecurityScanner.ScanResult result = new ExternalSkillSandboxSecurityScanner(local, external)
                .scan("{}");

        assertThat(result.classification()).isEqualTo(DeterministicSkillSecurityScanner.SECRET_EXPOSED);
        verify(external, never()).scan(any());
    }

    @Test
    void mapsExternalDecisionsToStableCategoriesWithoutVendorDetails() {
        SkillSecurityScanner local = new ValidationOnlySkillSecurityScanner();
        ExternalSkillSandboxScanner clean = request -> new ExternalSkillSandboxScanner.SandboxScanResult(
                ExternalSkillSandboxScanner.Decision.CLEAN, "vendor-clean", "secret response");
        ExternalSkillSandboxScanner rejected = request -> new ExternalSkillSandboxScanner.SandboxScanResult(
                ExternalSkillSandboxScanner.Decision.REJECTED, "MALWARE-123", "secret package path");

        SkillSecurityScanner.ScanResult cleanResult = new ExternalSkillSandboxSecurityScanner(local, clean)
                .scan("{}");
        SkillSecurityScanner.ScanResult rejectedResult = new ExternalSkillSandboxSecurityScanner(local, rejected)
                .scan("{}");

        assertThat(cleanResult.status()).isEqualTo(SkillSecurityScanner.ScanResult.Status.PASS);
        assertThat(cleanResult.classification()).isEqualTo("CLEAN");
        assertThat(cleanResult.detail()).isNull();
        assertThat(rejectedResult.status()).isEqualTo(SkillSecurityScanner.ScanResult.Status.FAIL);
        assertThat(rejectedResult.classification()).isEqualTo("SANDBOX_REJECTED");
        assertThat(rejectedResult.detail()).doesNotContain("MALWARE").doesNotContain("secret");
    }

    @Test
    void mapsRuntimeFailureAndInvalidResponseToStableReviewResults() {
        SkillSecurityScanner local = new ValidationOnlySkillSecurityScanner();
        ExternalSkillSandboxScanner failed = request -> {
            throw new IllegalStateException("provider response contained secrets");
        };
        ExternalSkillSandboxScanner invalid = request -> new ExternalSkillSandboxScanner.SandboxScanResult(
                ExternalSkillSandboxScanner.Decision.CLEAN, null, null);

        SkillSecurityScanner.ScanResult failedResult = new ExternalSkillSandboxSecurityScanner(local, failed)
                .scan("{}");
        SkillSecurityScanner.ScanResult invalidResult = new ExternalSkillSandboxSecurityScanner(local, invalid)
                .scan("{}");

        assertThat(failedResult.status()).isEqualTo(SkillSecurityScanner.ScanResult.Status.REVIEW_REQUIRED);
        assertThat(failedResult.classification()).isEqualTo(ExternalSkillSandboxScanner.SANDBOX_UNAVAILABLE);
        assertThat(failedResult.detail()).doesNotContain("provider response");
        assertThat(invalidResult.status()).isEqualTo(SkillSecurityScanner.ScanResult.Status.REVIEW_REQUIRED);
        assertThat(invalidResult.classification()).isEqualTo(ExternalSkillSandboxSecurityScanner.SANDBOX_INVALID_RESPONSE);
    }

    @Test
    void replaysBoundedArchiveToLocalAndExternalScanner() {
        SkillSecurityScanner local = mock(SkillSecurityScanner.class);
        when(local.supportsArchiveScan()).thenReturn(true);
        when(local.scanArchive(any(), anyString())).thenReturn(new SkillSecurityScanner.ScanResult(
                SkillSecurityScanner.ScanResult.Status.PASS, "CLEAN", null));
        ExternalSkillSandboxScanner external = request -> {
            assertThat(request.archiveBytes()).containsExactly(1, 2, 3);
            return new ExternalSkillSandboxScanner.SandboxScanResult(
                    ExternalSkillSandboxScanner.Decision.CLEAN, "CLEAN", null);
        };

        SkillSecurityScanner.ScanResult result = new ExternalSkillSandboxSecurityScanner(local, external)
                .scanArchive(new ByteArrayInputStream(new byte[] {1, 2, 3}), "{}");

        assertThat(result.status()).isEqualTo(SkillSecurityScanner.ScanResult.Status.PASS);
        verify(local).scanArchive(any(), anyString());
    }

    @Test
    void rejectsArchiveLargerThanExternalBoundaryBeforeCallingScanner() {
        SkillSecurityScanner local = new ValidationOnlySkillSecurityScanner();
        int[] calls = {0};
        ExternalSkillSandboxScanner external = request -> {
            calls[0]++;
            return new ExternalSkillSandboxScanner.SandboxScanResult(
                    ExternalSkillSandboxScanner.Decision.CLEAN, "CLEAN", null);
        };

        byte[] oversized = new byte[50 * 1024 * 1024 + 1];
        SkillSecurityScanner.ScanResult result = new ExternalSkillSandboxSecurityScanner(local, external)
                .scanArchive(new ByteArrayInputStream(oversized), "{}");

        assertThat(result.status()).isEqualTo(SkillSecurityScanner.ScanResult.Status.REVIEW_REQUIRED);
        assertThat(result.classification()).isEqualTo(ExternalSkillSandboxScanner.SANDBOX_INPUT_TOO_LARGE);
        assertThat(calls[0]).isZero();
    }
}
