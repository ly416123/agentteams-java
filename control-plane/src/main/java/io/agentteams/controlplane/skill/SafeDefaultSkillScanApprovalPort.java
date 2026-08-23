package io.agentteams.controlplane.skill;

/**
 * Explicit fail-closed approval policy. A review-required scan stays pending until an operator
 * or an explicitly configured approval integration records an approval.
 */
public final class SafeDefaultSkillScanApprovalPort implements SkillScanApprovalPort {

    @Override
    public ApprovalStatus onReviewRequired(ApprovalRequest request) {
        return ApprovalStatus.PENDING;
    }
}
