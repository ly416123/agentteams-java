package io.agentteams.controlplane.skill;

import io.agentteams.controlplane.service.ResourceNotFoundException;
import io.agentteams.controlplane.storage.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Owns the direct-to-object-storage upload lifecycle for a skill version package. */
@Service
public final class SkillPackageStorageService {

    private static final String DEFAULT_CONTENT_TYPE = "application/gzip";
    private static final String PENDING = "PENDING";
    private static final String COMPLETED = "COMPLETED";

    private final SkillRepository repository;
    private final ObjectStorage storage;
    private final Clock clock;
    private final SkillSecurityScanner securityScanner;

    @Autowired
    public SkillPackageStorageService(SkillRepository repository, ObjectProvider<ObjectStorage> storage,
            Clock clock, ObjectProvider<SkillSecurityScanner> scanners) {
        this(repository, storage.getIfAvailable(), clock,
                scanners.getIfAvailable(ValidationOnlySkillSecurityScanner::new));
    }

    public SkillPackageStorageService(SkillRepository repository, ObjectStorage storage, Clock clock) {
        this(repository, storage, clock, new ValidationOnlySkillSecurityScanner());
    }

    public SkillPackageStorageService(SkillRepository repository, ObjectStorage storage, Clock clock,
            SkillSecurityScanner securityScanner) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.storage = storage;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.securityScanner = Objects.requireNonNull(securityScanner, "securityScanner");
    }

    boolean archiveScanningAvailable() {
        return storage != null && securityScanner.supportsArchiveScan();
    }

    public SkillPackageUpload prepareUpload(UUID skillId, UUID versionId, PackageUploadInput input) {
        Objects.requireNonNull(input, "input");
        requireStorage();
        SkillVersionRecord version = versionForSkill(skillId, versionId);
        if (input.sizeBytes() > SkillPackageValidator.DEFAULT_MAX_PACKAGE_BYTES) {
            throw new SkillPackageValidationException("package size exceeds the configured maximum");
        }
        Duration expiry = input.expiry() == null ? Duration.ofMinutes(15) : input.expiry();
        if (expiry.isNegative() || expiry.isZero()) {
            throw new IllegalArgumentException("expiry must be positive");
        }
        String contentType = input.contentType() == null || input.contentType().isBlank()
                ? DEFAULT_CONTENT_TYPE : input.contentType().trim();
        String key = SkillPackageStoragePaths.versionPackage(skillId, versionId);
        URL uploadUrl = storage.presignPut(key, contentType, expiry);
        URL downloadUrl = storage.presignGet(key, expiry);
        SkillVersionRecord pending = repository.markPackageUploadPending(skillId, versionId, key,
                input.sizeBytes(), normalizeSha256(input.sha256()), clock.instant());
        return new SkillPackageUpload(pending.skillId(), pending.id(), key,
                input.sizeBytes(), normalizeSha256(input.sha256()),
                uploadUrl, downloadUrl);
    }

    public SkillVersionRecord completeUpload(UUID skillId, UUID versionId) {
        requireStorage();
        SkillVersionRecord version = versionForSkill(skillId, versionId);
        if (COMPLETED.equals(version.packageUploadStatus())) {
            return version;
        }
        if (!PENDING.equals(version.packageUploadStatus()) || version.packageStorageKey() == null
                || version.packageSizeBytes() == null || version.packageSha256() == null) {
            throw new SkillPackageValidationException("skill package upload is not prepared");
        }
        String expectedKey = SkillPackageStoragePaths.versionPackage(skillId, versionId);
        if (!expectedKey.equals(version.packageStorageKey())) {
            throw new SkillPackageValidationException("skill package storage key is invalid");
        }
        PackageVerification verification = verify(version.packageStorageKey(), version.packageSha256(),
                version.packageSizeBytes());
        scanArchive(version);
        return repository.completePackageUpload(skillId, versionId, verification.sizeBytes(),
                verification.sha256(), clock.instant());
    }

    /** Re-checks the immutable uploaded package immediately before publication. */
    public SkillSecurityScanner.ScanResult scanCompletedPackage(UUID skillId, UUID versionId) {
        requireStorage();
        SkillVersionRecord version = versionForSkill(skillId, versionId);
        if (!COMPLETED.equals(version.packageUploadStatus()) || version.packageStorageKey() == null
                || !SkillPackageStoragePaths.isVersionPackage(skillId, versionId, version.packageStorageKey())) {
            throw new SkillPackageValidationException("skill package is not available");
        }
        return scanArchiveResult(version);
    }

    public URL prepareDownload(UUID skillId, UUID versionId, Duration expiry) {
        requireStorage();
        SkillVersionRecord version = versionForSkill(skillId, versionId);
        if (!COMPLETED.equals(version.packageUploadStatus()) || version.packageStorageKey() == null
                || !SkillPackageStoragePaths.isVersionPackage(skillId, versionId, version.packageStorageKey())) {
            throw new SkillPackageValidationException("skill package is not available");
        }
        Duration effectiveExpiry = expiry == null ? Duration.ofMinutes(15) : expiry;
        if (effectiveExpiry.isNegative() || effectiveExpiry.isZero()) {
            throw new IllegalArgumentException("expiry must be positive");
        }
        return storage.presignGet(version.packageStorageKey(), effectiveExpiry);
    }

    private PackageVerification verify(String storageKey, String expectedSha256, long expectedSize) {
        try (InputStream input = storage.download(storageKey)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long size = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > SkillPackageValidator.DEFAULT_MAX_PACKAGE_BYTES) {
                    throw new SkillPackageValidationException("package exceeds the configured maximum");
                }
                digest.update(buffer, 0, read);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (size != expectedSize || !actual.equalsIgnoreCase(expectedSha256)) {
                throw new SkillPackageValidationException("skill package checksum or size mismatch");
            }
            return new PackageVerification(size, actual);
        } catch (IOException error) {
            throw new SkillPackageValidationException("skill package verification failed", error);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private void scanArchive(SkillVersionRecord version) {
        if (!securityScanner.supportsArchiveScan()) {
            return;
        }
        SkillSecurityScanner.ScanResult result = scanArchiveResult(version);
        if (result == null) {
            repository.markSecurityScan(version.skillId(), version.id(), "FAILED", clock.instant());
            throw new SkillPackageValidationException("skill package security scan failed: SCAN_INVALID_RESULT");
        }
        if (result.status() == SkillSecurityScanner.ScanResult.Status.REVIEW_REQUIRED) {
            // Upload completion is not publication. Preserve the package so the SkillService can
            // hand the immutable version to its approval boundary and keep the default fail-closed.
            repository.markSecurityScan(version.skillId(), version.id(), "FAILED", clock.instant());
            return;
        }
        if (!isPassed(result.status())) {
            repository.markSecurityScan(version.skillId(), version.id(), "FAILED", clock.instant());
            throw new SkillPackageValidationException("skill package security scan failed: "
                    + result.classification());
        }
        repository.markSecurityScan(version.skillId(), version.id(), "PASSED", clock.instant());
    }

    private SkillSecurityScanner.ScanResult scanArchiveResult(SkillVersionRecord version) {
        try (InputStream input = storage.download(version.packageStorageKey())) {
            return securityScanner.scanArchive(input, version.manifestJson());
        } catch (IOException error) {
            throw new SkillPackageValidationException("skill package archive scan failed", error);
        }
    }

    private static boolean isPassed(SkillSecurityScanner.ScanResult.Status status) {
        return status == SkillSecurityScanner.ScanResult.Status.PASS
                || status == SkillSecurityScanner.ScanResult.Status.PASSED;
    }

    private SkillVersionRecord versionForSkill(UUID skillId, UUID versionId) {
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(versionId, "versionId");
        SkillVersionRecord version = repository.findVersionById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("skill version", versionId));
        if (!skillId.equals(version.skillId())) {
            throw new ResourceNotFoundException("skill version", versionId);
        }
        return version;
    }

    private void requireStorage() {
        if (storage == null) {
            throw new IllegalStateException("skill package object storage is not configured");
        }
    }

    static String normalizeSha256(String value) {
        if (value == null || value.isBlank()) {
            throw new SkillPackageValidationException("package sha256 is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("sha256:")) {
            normalized = normalized.substring("sha256:".length());
        }
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new SkillPackageValidationException("package sha256 must be 64 hexadecimal characters");
        }
        return normalized;
    }

    public record PackageUploadInput(long sizeBytes, String sha256, String contentType, Duration expiry) {
        public PackageUploadInput {
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
            normalizeSha256(sha256);
        }
    }

    public record SkillPackageUpload(UUID skillId, UUID versionId, String storageKey, long sizeBytes,
            String sha256, URL uploadUrl, URL downloadUrl) {
        public SkillPackageUpload {
            Objects.requireNonNull(skillId, "skillId");
            Objects.requireNonNull(versionId, "versionId");
            if (!SkillPackageStoragePaths.isVersionPackage(skillId, versionId, storageKey)) {
                throw new IllegalArgumentException("storageKey is not valid for this skill version");
            }
            Objects.requireNonNull(uploadUrl, "uploadUrl");
            Objects.requireNonNull(downloadUrl, "downloadUrl");
        }
    }

    private record PackageVerification(long sizeBytes, String sha256) {
    }
}
