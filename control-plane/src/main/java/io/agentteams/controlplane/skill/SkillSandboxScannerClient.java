package io.agentteams.controlplane.skill;

import java.util.Objects;

/**
 * SPI for an isolated malware/package scanner. Implementations belong to the deployment and may
 * call a sandbox service, but must return classification metadata only; package contents and
 * scanner response bodies are never part of the public SkillSecurityScanner result.
 */
public interface SkillSandboxScannerClient {

    ScanResult scan(ScanRequest request) throws Exception;

    record ScanRequest(String manifestJson, byte[] archiveBytes) {
        public ScanRequest {
            Objects.requireNonNull(manifestJson, "manifestJson");
            archiveBytes = archiveBytes == null ? null : archiveBytes.clone();
        }

        @Override
        public byte[] archiveBytes() {
            return archiveBytes == null ? null : archiveBytes.clone();
        }

        @Override
        public String toString() {
            return "ScanRequest[manifestPresent=" + !manifestJson.isBlank()
                    + ", archiveBytes=" + (archiveBytes == null ? "absent" : archiveBytes.length) + "]";
        }
    }

    record ScanResult(Decision decision, String classification, String detail) {
        public ScanResult {
            if (decision == null) throw new IllegalArgumentException("decision is required");
            if (classification != null && classification.length() > 120) {
                throw new IllegalArgumentException("classification must be at most 120 characters");
            }
            if (detail != null && detail.length() > 500) {
                throw new IllegalArgumentException("detail must be at most 500 characters");
            }
        }
    }

    enum Decision {
        CLEAN, REJECTED, REVIEW_REQUIRED
    }

    final class UnavailableException extends RuntimeException {
        public UnavailableException(String message) {
            super(message);
        }

        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A transport timeout that callers can map to a stable review classification. */
    final class TimeoutException extends RuntimeException {
        public TimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The provider answered, but its result did not satisfy the client contract. */
    final class InvalidResultException extends RuntimeException {
        public InvalidResultException(String message) {
            super(message);
        }

        public InvalidResultException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
