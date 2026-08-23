package io.agentteams.controlplane.skill;

import java.util.Objects;

/** Transport-neutral SPI for an isolated external Skill package scanner. */
@FunctionalInterface
public interface ExternalSkillSandboxScanner {
    String SANDBOX_TIMEOUT = "SANDBOX_TIMEOUT";
    String SANDBOX_UNAVAILABLE = "SANDBOX_UNAVAILABLE";
    String SANDBOX_INVALID_RESULT = "SANDBOX_INVALID_RESULT";
    String SANDBOX_FAILED = "SANDBOX_FAILED";
    String SANDBOX_INPUT_TOO_LARGE = "SANDBOX_INPUT_TOO_LARGE";
    String SANDBOX_ARCHIVE_READ_FAILED = "SANDBOX_ARCHIVE_READ_FAILED";

    SandboxScanResult scan(SandboxScanRequest request);

    default boolean supportsArchiveScan() {
        return true;
    }

    record SandboxScanRequest(String manifestJson, byte[] archiveBytes) {
        public SandboxScanRequest {
            Objects.requireNonNull(manifestJson, "manifestJson");
            if (archiveBytes != null) archiveBytes = archiveBytes.clone();
        }

        @Override
        public byte[] archiveBytes() {
            return archiveBytes == null ? null : archiveBytes.clone();
        }
    }

    record SandboxScanResult(Decision decision, String classification, String vendorDetail) { }

    enum Decision {
        CLEAN, REJECTED, REVIEW_REQUIRED
    }

    final class UnavailableException extends RuntimeException {
        public UnavailableException(String message) {
            super(message);
        }
    }
}
