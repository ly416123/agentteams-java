package io.agentteams.controlplane.skill;

import java.util.Objects;

/** Pluggable scanner contract; implementations must never return package secrets in details. */
public interface SkillSecurityScanner {

    ScanResult scan(String manifestJson);

    record ScanResult(Status status, String classification, String detail) {
        public ScanResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(classification, "classification");
            if (detail != null && detail.length() > 500) {
                throw new IllegalArgumentException("detail must be at most 500 characters");
            }
        }

        public enum Status {
            PASSED, FAILED
        }
    }
}
