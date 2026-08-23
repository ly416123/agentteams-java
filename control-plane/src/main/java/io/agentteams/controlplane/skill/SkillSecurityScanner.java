package io.agentteams.controlplane.skill;

import java.io.InputStream;
import java.util.Objects;

/** Pluggable scanner contract; implementations must never return package secrets in details. */
public interface SkillSecurityScanner {

    ScanResult scan(String manifestJson);

    /**
     * Whether this scanner should be given the uploaded package bytes. The default scanner is
     * intentionally manifest-only so enabling archive inspection remains an explicit choice.
     */
    default boolean supportsArchiveScan() {
        return false;
    }

    /** Scans an uploaded archive together with its already validated manifest. */
    default ScanResult scanArchive(InputStream archive, String manifestJson) {
        Objects.requireNonNull(archive, "archive");
        return scan(manifestJson);
    }

    record ScanResult(Status status, String classification, String detail) {
        public ScanResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(classification, "classification");
            if (detail != null && detail.length() > 500) {
                throw new IllegalArgumentException("detail must be at most 500 characters");
            }
        }

        public enum Status {
            /** New scanner vocabulary. */
            PASS, FAIL, REVIEW_REQUIRED,
            /** Legacy values retained for existing scanners and persisted state. */
            @Deprecated
            PASSED,
            @Deprecated
            FAILED
        }
    }
}
