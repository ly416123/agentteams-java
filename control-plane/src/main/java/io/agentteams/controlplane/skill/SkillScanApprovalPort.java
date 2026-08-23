package io.agentteams.controlplane.skill;

import java.util.Objects;
import java.util.UUID;

/**
 * Boundary for resolving a Skill security scan that requires human or enterprise approval.
 *
 * <p>The request deliberately contains identifiers and low-cardinality scan metadata only. It
 * never carries a manifest, archive bytes, or vendor response details. Implementations may return
 * {@link ApprovalStatus#PENDING} and complete the approval later through the existing Skill review
 * operation.</p>
 */
@FunctionalInterface
public interface SkillScanApprovalPort {

    ApprovalStatus onReviewRequired(ApprovalRequest request);

    /** Safe fail-closed policy used when no approval integration is configured. */
    static SkillScanApprovalPort safeDefault() {
        return request -> ApprovalStatus.PENDING;
    }

    record ApprovalRequest(UUID skillId, UUID versionId, String classification, String digest) {
        public ApprovalRequest {
            Objects.requireNonNull(skillId, "skillId");
            Objects.requireNonNull(versionId, "versionId");
            if (classification == null || classification.isBlank()) {
                throw new IllegalArgumentException("classification is required");
            }
            if (classification.length() > 120) {
                throw new IllegalArgumentException("classification must be at most 120 characters");
            }
            if (digest == null || digest.isBlank()) {
                throw new IllegalArgumentException("digest is required");
            }
        }
    }

    enum ApprovalStatus {
        PENDING, APPROVED, REJECTED
    }
}
