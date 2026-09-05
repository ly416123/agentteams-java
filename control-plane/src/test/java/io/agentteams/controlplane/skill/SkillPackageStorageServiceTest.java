package io.agentteams.controlplane.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class SkillPackageStorageServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");
    private static final String SHA256 = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a";

    @Test
    void preparesWithServerOwnedKeyAndCompletesOnlyAfterChecksumAndSizeMatch() throws Exception {
        SkillRepository repository = mock(SkillRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        SkillVersionRecord draft = version(skillId, versionId, "NOT_STARTED", null, null, null);
        SkillVersionRecord pending = version(skillId, versionId, "PENDING",
                SkillPackageStoragePaths.versionPackage(skillId, versionId), 4L, SHA256);
        SkillVersionRecord completed = version(skillId, versionId, "COMPLETED",
                SkillPackageStoragePaths.versionPackage(skillId, versionId), 4L, SHA256);
        when(repository.findVersionById(versionId)).thenReturn(Optional.of(draft), Optional.of(pending));
        when(repository.markPackageUploadPending(eq(skillId), eq(versionId), any(), eq(4L), eq(SHA256), eq(NOW)))
                .thenReturn(pending);
        when(repository.completePackageUpload(eq(skillId), eq(versionId), eq(4L), eq(SHA256), eq(NOW)))
                .thenReturn(completed);
        when(storage.presignPut(any(), any(), any())).thenReturn(new URL("https://storage/upload"));
        when(storage.presignGet(any(), any())).thenReturn(new URL("https://storage/download"));
        when(storage.download(SkillPackageStoragePaths.versionPackage(skillId, versionId)))
                .thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));

        SkillPackageStorageService service = new SkillPackageStorageService(repository, storage,
                Clock.fixed(NOW, ZoneOffset.UTC));
        SkillPackageStorageService.SkillPackageUpload upload = service.prepareUpload(skillId, versionId,
                new SkillPackageStorageService.PackageUploadInput(4, SHA256, null, null));

        assertThat(upload.storageKey()).isEqualTo(SkillPackageStoragePaths.versionPackage(skillId, versionId));
        assertThat(service.completeUpload(skillId, versionId)).isSameAs(completed);
        verify(repository).completePackageUpload(skillId, versionId, 4L, SHA256, NOW);
    }

    @Test
    void rejectsMismatchedPackageWithoutMarkingCompleted() {
        SkillRepository repository = mock(SkillRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        SkillVersionRecord pending = version(skillId, versionId, "PENDING",
                SkillPackageStoragePaths.versionPackage(skillId, versionId), 3L, SHA256);
        when(repository.findVersionById(versionId)).thenReturn(Optional.of(pending));
        when(storage.download(any())).thenReturn(new ByteArrayInputStream(new byte[] {1, 2}));

        SkillPackageStorageService service = new SkillPackageStorageService(repository, storage,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.completeUpload(skillId, versionId))
                .isInstanceOf(SkillPackageValidationException.class)
                .hasMessage("skill package checksum or size mismatch");
    }

    @Test
    void rejectsCrossSkillVersionAndUnsafeKeys() {
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        assertThat(SkillPackageStoragePaths.versionPackage(skillId, versionId))
                .doesNotContain("..")
                .isEqualTo("skills/" + skillId + "/versions/" + versionId + "/package.tar.gz");
        assertThat(SkillPackageStoragePaths.isVersionPackage(skillId, versionId,
                "skills/" + UUID.randomUUID() + "/versions/" + versionId + "/package.tar.gz")).isFalse();
        assertThatThrownBy(() -> SkillPackageStorageService.normalizeSha256("../secret"))
                .isInstanceOf(SkillPackageValidationException.class);
    }

    @Test
    void scansArchiveBeforeCompletingWhenDeterministicScannerIsEnabled() throws Exception {
        SkillRepository repository = mock(SkillRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        SkillSecurityScanner scanner = mock(SkillSecurityScanner.class);
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        SkillVersionRecord pending = version(skillId, versionId, "PENDING",
                SkillPackageStoragePaths.versionPackage(skillId, versionId), 4L, SHA256);
        SkillVersionRecord completed = version(skillId, versionId, "COMPLETED",
                SkillPackageStoragePaths.versionPackage(skillId, versionId), 4L, SHA256);
        when(scanner.supportsArchiveScan()).thenReturn(true);
        when(scanner.scanArchive(any(), eq(pending.manifestJson())))
                .thenReturn(new SkillSecurityScanner.ScanResult(SkillSecurityScanner.ScanResult.Status.PASS,
                        "CLEAN", null));
        when(repository.findVersionById(versionId)).thenReturn(Optional.of(pending));
        when(repository.markSecurityScan(eq(skillId), eq(versionId), eq("PASSED"), eq(NOW)))
                .thenReturn(pending);
        when(repository.completePackageUpload(eq(skillId), eq(versionId), eq(4L), eq(SHA256), eq(NOW)))
                .thenReturn(completed);
        when(storage.download(SkillPackageStoragePaths.versionPackage(skillId, versionId)))
                .thenAnswer(ignored -> new ByteArrayInputStream(new byte[] {1, 2, 3, 4}))
                .thenAnswer(ignored -> new ByteArrayInputStream(zip("SKILL.md", "safe")));

        SkillPackageStorageService service = new SkillPackageStorageService(repository, storage,
                Clock.fixed(NOW, ZoneOffset.UTC), scanner);

        assertThat(service.completeUpload(skillId, versionId)).isSameAs(completed);
        verify(scanner).scanArchive(any(), eq(pending.manifestJson()));
        verify(repository).markSecurityScan(skillId, versionId, "PASSED", NOW);
        verify(repository).completePackageUpload(skillId, versionId, 4L, SHA256, NOW);
    }

    @Test
    void blocksCompletionOnStableArchiveClassification() {
        SkillRepository repository = mock(SkillRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        SkillSecurityScanner scanner = mock(SkillSecurityScanner.class);
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        SkillVersionRecord pending = version(skillId, versionId, "PENDING",
                SkillPackageStoragePaths.versionPackage(skillId, versionId), 4L, SHA256);
        when(scanner.supportsArchiveScan()).thenReturn(true);
        when(scanner.scanArchive(any(), eq(pending.manifestJson())))
                .thenReturn(new SkillSecurityScanner.ScanResult(SkillSecurityScanner.ScanResult.Status.FAIL,
                        DeterministicSkillSecurityScanner.PATH_TRAVERSAL, "unsafe archive path"));
        when(repository.findVersionById(versionId)).thenReturn(Optional.of(pending));
        when(storage.download(any()))
                .thenAnswer(ignored -> new ByteArrayInputStream(new byte[] {1, 2, 3, 4}))
                .thenAnswer(ignored -> new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));

        SkillPackageStorageService service = new SkillPackageStorageService(repository, storage,
                Clock.fixed(NOW, ZoneOffset.UTC), scanner);

        assertThatThrownBy(() -> service.completeUpload(skillId, versionId))
                .isInstanceOf(SkillPackageValidationException.class)
                .hasMessage("skill package security scan failed: PATH_TRAVERSAL");
        verify(repository, never()).completePackageUpload(any(), any(), anyLong(), any(), any());
    }

    @Test
    void completesUploadForReviewRequiredArchiveAndLeavesPublicationToApprovalBoundary() {
        SkillRepository repository = mock(SkillRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        SkillSecurityScanner scanner = mock(SkillSecurityScanner.class);
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        SkillVersionRecord pending = version(skillId, versionId, "PENDING",
                SkillPackageStoragePaths.versionPackage(skillId, versionId), 4L, SHA256);
        SkillVersionRecord completed = version(skillId, versionId, "COMPLETED",
                SkillPackageStoragePaths.versionPackage(skillId, versionId), 4L, SHA256);
        when(scanner.supportsArchiveScan()).thenReturn(true);
        when(scanner.scanArchive(any(), eq(pending.manifestJson())))
                .thenReturn(new SkillSecurityScanner.ScanResult(
                        SkillSecurityScanner.ScanResult.Status.REVIEW_REQUIRED, "SANDBOX_TIMEOUT", null));
        when(repository.findVersionById(versionId)).thenReturn(Optional.of(pending));
        when(repository.markSecurityScan(eq(skillId), eq(versionId), eq("FAILED"), eq(NOW)))
                .thenReturn(pending);
        when(repository.completePackageUpload(eq(skillId), eq(versionId), eq(4L), eq(SHA256), eq(NOW)))
                .thenReturn(completed);
        when(storage.download(SkillPackageStoragePaths.versionPackage(skillId, versionId)))
                .thenAnswer(ignored -> new ByteArrayInputStream(new byte[] {1, 2, 3, 4}))
                .thenAnswer(ignored -> new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));

        SkillPackageStorageService service = new SkillPackageStorageService(repository, storage,
                Clock.fixed(NOW, ZoneOffset.UTC), scanner);

        assertThat(service.completeUpload(skillId, versionId)).isSameAs(completed);
        verify(repository).markSecurityScan(skillId, versionId, "FAILED", NOW);
        verify(repository).completePackageUpload(skillId, versionId, 4L, SHA256, NOW);
    }

    private static byte[] zip(String name, String content) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static SkillVersionRecord version(UUID skillId, UUID versionId, String status, String key,
            Long size, String sha256) {
        return new SkillVersionRecord(versionId, skillId, "1.0.0",
                "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "{\"name\":\"skill\",\"description\":\"desc\",\"entry\":\"SKILL.md\",\"sizeBytes\":4}",
                "PRIVATE", "DRAFT", NOW, NOW, 0, "NOT_SCANNED", "PENDING", key, size, sha256, status);
    }
}
